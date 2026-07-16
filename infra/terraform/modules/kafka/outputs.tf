output "bootstrap_brokers_tls" {
  value = var.enable_kafka ? aws_msk_cluster.main[0].bootstrap_brokers_tls : ""
}
