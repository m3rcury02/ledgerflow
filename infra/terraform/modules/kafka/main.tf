resource "aws_security_group" "kafka" {
  count       = var.enable_kafka ? 1 : 0
  name        = "ledgerflow-kafka-sg-${var.environment}"
  description = "Allow inbound traffic from ECS tasks to Kafka"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Kafka from ECS"
    from_port       = 9092
    to_port         = 9094
    protocol        = "tcp"
    security_groups = [var.ecs_security_group_id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_msk_cluster" "main" {
  count                  = var.enable_kafka ? 1 : 0
  cluster_name           = "ledgerflow-msk-${var.environment}"
  kafka_version          = "3.5.1"
  number_of_broker_nodes = 2

  broker_node_group_info {
    instance_type   = "kafka.t3.small"
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.kafka[0].id]

    storage_info {
      ebs_storage_info {
        volume_size = 50
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
}
