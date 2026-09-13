# Product page for In-N-Out-Work: a static site served by unprivileged nginx (runs as a
# non-root user and listens on 8080 inside the container).
#
#     docker build -t in-n-out-work/product-site product-site
#     docker run -d -p 80:8080 in-n-out-work/product-site
FROM nginxinc/nginx-unprivileged:1.28-alpine

# Where "Open the app" sends people. The app runs on the same host on port 8444, so the default
# keeps whatever hostname the visitor used ($host is an nginx variable, filled in per request).
# Override with a full URL when the app is reached some other way, e.g. APP_URL=https://app.example.com/
ENV APP_URL=https://\$host:8444/

# The image renders /etc/nginx/templates/*.template into conf.d at start-up, substituting
# environment variables such as APP_URL (nginx's own $variables are left alone).
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY site/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
