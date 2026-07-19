resource "aws_ecs_cluster" "this" {
  name = "${var.project_name}-${var.environment}"

  setting {
    name  = "containerInsights"
    value = var.enable_container_insights ? "enabled" : "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

locals {
  # Same background-job flag split Milestone 4's Helm chart uses (see
  # deploy/helm/ledgerflow/templates/deployment-api.yaml /
  # deploy/helm/ledgerflow/templates/deployment-worker.yaml): api runs purely
  # request/response, worker runs purely background processing.
  # Property names verified against application/src/main/resources/application.yaml, not
  # assumed - in particular the datasource takes one LEDGERFLOW_DB_URL (a full JDBC URL),
  # not separate host/port/name properties.
  shared_environment = [
    { name = "LEDGERFLOW_MANAGEMENT_PORT", value = "8081" },
    { name = "LEDGERFLOW_DEPLOYMENT_ENVIRONMENT", value = var.environment },
    { name = "LEDGERFLOW_OAUTH2_ISSUER", value = var.oauth2_issuer_uri },
    # Keycloak's realm JWKS path convention, matching the out-of-scope identity-provider
    # placeholder (see variables.tf, oauth2_issuer_uri) - this property has no application
    # default and must resolve to something once a real identity provider exists.
    { name = "LEDGERFLOW_OAUTH2_JWK_SET_URI", value = "${var.oauth2_issuer_uri}/protocol/openid-connect/certs" },
    { name = "LEDGERFLOW_KAFKA_BOOTSTRAP_SERVERS", value = var.kafka_bootstrap_servers },
    { name = "LEDGERFLOW_DB_URL", value = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}" },
  ]

  # RDS's manage_master_user_password stores the credential as a JSON secret
  # ({"username":...,"password":...,...}), not a scalar - project the two fields the
  # application actually reads (LEDGERFLOW_DB_USERNAME / LEDGERFLOW_DB_PASSWORD) individually
  # via ECS's "secretArn:jsonKey::" syntax rather than injecting the whole JSON blob.
  db_credential_secrets = [
    { name = "LEDGERFLOW_DB_USERNAME", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:username::" },
    { name = "LEDGERFLOW_DB_PASSWORD", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::" },
  ]

  api_environment = concat(local.shared_environment, [
    { name = "LEDGERFLOW_RECOVERY_WORKER_ENABLED", value = "false" },
    { name = "LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED", value = "false" },
    { name = "LEDGERFLOW_NOTIFICATION_CONSUMER_ENABLED", value = "false" },
    { name = "LEDGERFLOW_NOTIFICATION_DLT_CONSUMER_ENABLED", value = "false" },
  ])

  worker_environment = concat(local.shared_environment, [
    { name = "LEDGERFLOW_RECOVERY_WORKER_ENABLED", value = "true" },
    { name = "LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED", value = "true" },
    { name = "LEDGERFLOW_NOTIFICATION_CONSUMER_ENABLED", value = "true" },
    { name = "LEDGERFLOW_NOTIFICATION_DLT_CONSUMER_ENABLED", value = "true" },
  ])

  container_image = "${aws_ecr_repository.app.repository_url}:${var.container_image_tag}"
}

# --- api ---------------------------------------------------------------

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.project_name}-${var.environment}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.api_execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # Fargate does not support the `tmpfs` linux parameter (EC2 launch type only) - a writable
  # /tmp under readonlyRootFilesystem instead uses an ephemeral task-level volume, mounted
  # into the container below.
  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name      = "ledgerflow-api"
      image     = local.container_image
      essential = true

      portMappings = [
        { containerPort = 8080, protocol = "tcp" },
        { containerPort = 8081, protocol = "tcp" },
      ]

      environment = local.api_environment
      secrets     = local.db_credential_secrets

      readonlyRootFilesystem = true
      mountPoints = [
        { sourceVolume = "tmp", containerPath = "/tmp", readOnly = false },
      ]

      healthCheck = {
        command     = ["CMD-SHELL", "wget -q -O- http://localhost:8081/actuator/health/liveness || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 90
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.api.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "api"
        }
      }
    }
  ])

  tags = {
    Name = "${var.project_name}-${var.environment}-api"
  }
}

resource "aws_ecs_service" "api" {
  name            = "${var.project_name}-${var.environment}-api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count

  launch_type = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "ledgerflow-api"
    container_port   = 8080
  }

  # Fargate tasks have no host to drain slowly; a short grace period is enough for the
  # application's own graceful-shutdown handling (docs/container-hardening.md) to finish.
  health_check_grace_period_seconds = 90

  lifecycle {
    ignore_changes = [desired_count] # owned by Application Auto Scaling once running
  }

  depends_on = [aws_lb_listener.http]
}

resource "aws_appautoscaling_target" "api" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.api_min_count
  max_capacity       = var.api_max_count
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${var.project_name}-${var.environment}-api-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = var.api_target_cpu_percent
    scale_in_cooldown  = 300 # matches the HPA's default scaleDown.stabilizationWindowSeconds observed in Milestone 4
    scale_out_cooldown = 60
  }
}

# --- worker ------------------------------------------------------------
#
# No load_balancer block, no Application Auto Scaling target: fixed-size background
# processing, never reachable from the ALB or any public path - the same "no rule permits
# port 8080 at all" property Milestone 4's worker NetworkPolicy enforced, realized here by
# the worker security group having no ingress rule at all (security_groups.tf).

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.project_name}-${var.environment}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = aws_iam_role.worker_execution.arn
  task_role_arn            = aws_iam_role.worker_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # See the api task definition above: Fargate does not support the `tmpfs` linux parameter.
  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name      = "ledgerflow-worker"
      image     = local.container_image
      essential = true

      portMappings = [
        { containerPort = 8081, protocol = "tcp" },
      ]

      environment = local.worker_environment
      secrets     = local.db_credential_secrets

      readonlyRootFilesystem = true
      mountPoints = [
        { sourceVolume = "tmp", containerPath = "/tmp", readOnly = false },
      ]

      healthCheck = {
        command     = ["CMD-SHELL", "wget -q -O- http://localhost:8081/actuator/health/liveness || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 90
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.worker.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "worker"
        }
      }
    }
  ])

  tags = {
    Name = "${var.project_name}-${var.environment}-worker"
  }
}

resource "aws_ecs_service" "worker" {
  name            = "${var.project_name}-${var.environment}-worker"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count

  launch_type = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = false
  }
}
