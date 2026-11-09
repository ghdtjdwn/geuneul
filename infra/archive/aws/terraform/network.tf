# VPC + 서브넷. 비용 절감: NAT 게이트웨이(~$32/월) 없음.
# Fargate 태스크는 퍼블릭 서브넷 + 퍼블릭 IP로 ECR/인터넷 접근(SG로 잠금).
# RDS는 프라이빗 서브넷(인터넷 egress 불필요, ECS SG에서만 접근).

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "${var.project}-vpc" }
}

resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project}-igw" }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index) # 10.0.0.0/24, 10.0.1.0/24
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true
  tags                    = { Name = "${var.project}-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10) # 10.0.10.0/24, 10.0.11.0/24
  availability_zone = data.aws_availability_zones.available.names[count.index]
  tags              = { Name = "${var.project}-private-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
  tags = { Name = "${var.project}-public-rt" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# --- 보안 그룹 (ALB → ECS → RDS 로 계단식 잠금) ---
# CloudFront가 커스텀 오리진(ALB)을 칠 때 나가는 IP 대역(AWS 관리형 prefix list).
# ALB ingress를 이 대역으로만 제한 = 인터넷에서 ALB 직접 타격을 차단하고 CloudFront(HTTPS)만 진입.
# 브라우저·BFF(Vercel)는 전부 CloudFront 경유라 영향 없음(ADR-0028).
data "aws_ec2_managed_prefix_list" "cloudfront_origin" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "alb" {
  name = "${var.project}-alb-sg"
  # description 문자열은 SG immutable이라 그대로 둔다(바꾸면 SG replace→ALB 다운타임). 실제 규칙은 아래
  # ingress가 CloudFront origin-facing prefix list로 진입을 제한한다(ADR-0028).
  description = "ALB: HTTP from internet only"
  vpc_id      = aws_vpc.main.id
  ingress {
    description     = "HTTP from CloudFront edge only (ADR-0028)"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront_origin.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.project}-alb-sg" }
}

resource "aws_security_group" "ecs" {
  name        = "${var.project}-ecs-sg"
  description = "ECS tasks: inbound 8080 from ALB only"
  vpc_id      = aws_vpc.main.id
  ingress {
    description     = "App port from ALB only"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.project}-ecs-sg" }
}

resource "aws_security_group" "rds" {
  name        = "${var.project}-rds-sg"
  description = "RDS: inbound 5432 from ECS tasks only"
  vpc_id      = aws_vpc.main.id
  ingress {
    description     = "Postgres from ECS tasks only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.project}-rds-sg" }
}
