"""The host port is loopback-only; HTTPS terminates at the trusted reverse proxy."""
bind = "0.0.0.0:58743"
workers = 1
threads = 4
timeout = 300
graceful_timeout = 30
worker_tmp_dir = "/run"
# Docker's bridge changes the proxy source address. Never publish this port directly to the Internet.
forwarded_allow_ips = "*"
accesslog = None
errorlog = "-"
loglevel = "warning"
