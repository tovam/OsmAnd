FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    FLIGHT_CLOUD_HOME=/app/data/library \
    TMPDIR=/run
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py deployment.py gunicorn.conf.py ./

# Numeric identity matches project-deployer.yml; persistent mount ownership is prepared by pd.
USER 10001:10001
EXPOSE 58743
CMD ["gunicorn", "--config", "gunicorn.conf.py", "deployment:create_service()"]
