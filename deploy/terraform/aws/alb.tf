resource "aws_lb" "this" {
  #checkov:skip=CKV_AWS_150:Deletion protection is left off for this never-applied reference design so teardown never needs an extra manual step beyond what's documented in docs/aws-terraform-design.md; enable it alongside db_deletion_protection for a real deployment.
  #checkov:skip=CKV_AWS_91:Access logging would require provisioning a dedicated S3 log-delivery bucket, which is out of scope for this milestone's literal service list (VPC/ECS Fargate/RDS/ECR/Secrets Manager/CloudWatch - no S3). A real deployment should add one.
  #checkov:skip=CKV2_AWS_20:HTTP->HTTPS redirect is enabled once acm_certificate_arn is set (see the http listener below); no real domain/certificate exists for this never-applied design, so HTTP-only is the static default this scanner evaluates.
  #checkov:skip=CKV2_AWS_28:AWS WAFv2 is a separate service with its own significant configuration surface (managed rule groups, rate limiting) outside this milestone's literal service list (VPC/ECS Fargate/RDS/ECR/Secrets Manager/CloudWatch). A real deployment should add a WAF ACL in front of this ALB.
  name = "${var.project_name}-${var.environment}"
  #trivy:ignore:AWS-0053 Internet-facing is intentional: this is the public entry point of a web application.
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  drop_invalid_header_fields = true

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }
}

resource "aws_lb_target_group" "api" {
  #checkov:skip=CKV_AWS_378:TLS terminates at the ALB listener (HTTPS once acm_certificate_arn is set); ALB-to-task traffic stays entirely within the private application subnets, which is the standard, correct place to terminate TLS for this topology.
  name        = "${var.project_name}-${var.environment}-api"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.this.id
  target_type = "ip"

  health_check {
    path                = "/actuator/health/readiness"
    port                = "8081"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-api"
  }
}

# HTTP listener: forwards directly when no ACM certificate is configured (this never-applied
# design has no real domain to issue one for); redirects to HTTPS once acm_certificate_arn is
# set.
#trivy:ignore:AWS-0054 Same as CKV_AWS_2/CKV_AWS_103 below - HTTPS is enabled once acm_certificate_arn is set.
resource "aws_lb_listener" "http" {
  #checkov:skip=CKV_AWS_2:HTTPS is enabled conditionally via acm_certificate_arn (see the https listener below); no real domain/certificate exists for this never-applied design, so HTTP-only is the default until one is supplied.
  #checkov:skip=CKV_AWS_103:See CKV_AWS_2 - TLS policy applies only once a real certificate is configured.
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = var.acm_certificate_arn == "" ? [1] : []
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.api.arn
    }
  }

  dynamic "default_action" {
    for_each = var.acm_certificate_arn == "" ? [] : [1]
    content {
      type = "redirect"

      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count = var.acm_certificate_arn == "" ? 0 : 1

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
