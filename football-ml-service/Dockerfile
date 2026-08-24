# Dockerfile for ML Prediction Service
FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN python -m pip install --upgrade pip -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com \
    && pip install --no-cache-dir -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com

# Copy app
COPY app.py .
COPY train.py .

# Create models directory (will be mounted or populated by train)
RUN mkdir -p /app/models

RUN useradd --system --uid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin football \
    && chown -R football:football /app
USER 10001

EXPOSE 5001

# Run with gunicorn for production
CMD ["gunicorn", "-b", "0.0.0.0:5001", "-w", "2", "--timeout", "120", "app:app"]
