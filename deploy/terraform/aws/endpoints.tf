# VPC endpoints instead of a NAT Gateway: everything the ECS tasks need to reach outside the
# VPC (pull images, ship logs, read the RDS-managed DB secret) stays on AWS's private network,
# never the public internet. See docs/aws-terraform-design.md, "Design decisions".

resource "aws_security_group" "vpc_endpoints" {
  name_prefix = "${var.project_name}-${var.environment}-vpce-"
  description = "Allow HTTPS from private application subnets to VPC interface endpoints."
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "HTTPS from private application subnets"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = var.private_app_subnet_cidrs
  }

  egress {
    description = "Endpoint ENIs do not need to initiate outbound traffic beyond the VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-vpce"
  }

  lifecycle {
    create_before_destroy = true
  }
}

locals {
  interface_endpoint_services = ["ecr.api", "ecr.dkr", "logs", "secretsmanager"]
}

resource "aws_vpc_endpoint" "interface" {
  for_each = toset(local.interface_endpoint_services)

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private_app[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = {
    Name = "${var.project_name}-${var.environment}-${each.value}"
  }
}

# Gateway endpoint (free, no ENIs): ECR image layers are stored in S3.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private_app.id]

  tags = {
    Name = "${var.project_name}-${var.environment}-s3"
  }
}
