FROM python:3.9-alpine

RUN addgroup -S zigbee-stats && adduser -S zigbee-stats -G zigbee-stats

USER zigbee-stats

WORKDIR /app

EXPOSE 8080

ENV PYTHONDONTWRITEBYTECODE 1
ENV PYTHONUNBUFFERED 1
ENV PATH /home/zigbee-stats/.local/bin:$PATH

COPY requirements.txt     /app

RUN pip install --no-cache-dir --disable-pip-version-check --upgrade pip
RUN pip install --no-cache-dir -r ./requirements.txt

COPY *.py                 /app/
COPY templates            /app/templates
COPY config-template.toml /app/

ENTRYPOINT python listener.py