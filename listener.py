#!/usr/bin/python3

import asyncio
import dataclasses
import json
import logging
from asyncio import CancelledError, Task
from datetime import datetime, timedelta
from functools import cmp_to_key
from pathlib import Path
from urllib.parse import urlparse

import toml
import websockets
from quart import Quart, render_template, request
from werkzeug.exceptions import BadRequest

FORMAT_HTML = "html"
FORMAT_TILE = "tile"
FORMATS = [FORMAT_HTML, FORMAT_TILE]


@dataclasses.dataclass
class LogMessage:
    name: str
    id: int
    profileId: int
    clusterId: int
    sourceEndpoint: int
    destinationEndpoint: int
    groupId: int
    sequence: int
    lastHopLqi: int
    lastHopRssi: int
    time: str
    type: str
    _date_time: datetime = dataclasses.field(default=None)

    @property
    def idHex(self) -> str:
        return f"{self.id:0>4X}"

    @property
    def dateTime(self):
        if not self._date_time:
            self._date_time = datetime.strptime(self.time, "%Y-%m-%d %H:%M:%S.%f")

        return self._date_time


def compare(item1: LogMessage, item2: LogMessage) -> int:
    lqi_diff = item1.lastHopLqi - item2.lastHopLqi
    return item1.lastHopRssi - item2.lastHopRssi if lqi_diff == 0 else lqi_diff


class ListenerQuartApp(Quart):
    def __init__(self):
        super(ListenerQuartApp, self).__init__(__name__)
        self.config.from_file("config.toml", load=toml.load)
        self.logger.setLevel(self.config.get("LOG_LEVEL", "WARNING"))

        self.is_shutting_down: bool = False
        self.background_task: Task = None

        hubitat_hub_url = self.config.get("HUBITAT_HUB_URL")
        if not hubitat_hub_url:
            raise ValueError("Invalid configuration in config.toml.  No HUBITAT_HUB_URL entry.")
        parsed_url = urlparse(hubitat_hub_url)
        self.ws_url: str = f"{'ws' if parsed_url.scheme == 'http' else 'wss'}://{parsed_url.netloc}/zigbeeLogsocket"
        self.logger.info(f"Hubitat Zigbee WebSocket URL: {self.ws_url}")

        self.last_saved_datetime: datetime = datetime.now()
        self.data_path: Path = Path(__file__).parent / "data.json"
        self.data: dict = {}
        try:
            if self.data_path.exists():
                with self.data_path.open("r") as rf:
                    self.data = json.load(rf)
        except:
            self.logger.exception(f"Failed to read data file {self.data_path}")


app = ListenerQuartApp()


def add_log_message(msg: LogMessage):
    app.data[msg.id] = msg.__dict__
    if app.last_saved_datetime < datetime.now() - timedelta(minutes=5):
        with app.data_path.open("w") as fs:
            json.dump(app.data, fs, indent=2)
            app.last_saved_datetime = datetime.now()


def get_data():
    return [LogMessage(**v) for v in app.data.values() if v["id"]]


def get_output_format():
    output_format = request.args.get("format", FORMAT_HTML)
    if output_format not in FORMATS:
        raise BadRequest(description=f"Invalid format {output_format}.  Valid formats: {FORMATS}")
    return output_format


async def task_zigbee_log_listener():
    while True:
        try:
            async with websockets.connect(app.ws_url) as websocket:
                app.logger.debug(f"Connected to {app.ws_url}")
                while True:
                    data_str = await websocket.recv()
                    data = json.loads(data_str)
                    app.logger.debug(f"Zigbee log message received: {data}")
                    msg = LogMessage(**data)
                    add_log_message(msg)
        except CancelledError:
            if app.is_shutting_down:
                app.logger.info("Shutting down")
                return
            app.logger.debug("WebSocket connection has been closed.  Will re-connect.")
        except:
            app.logger.exception("Exception has happened while communicating.  Will re-connect.")
        await asyncio.sleep(10)


@app.before_serving
async def startup():
    app.is_shutting_down = False
    app.background_task = asyncio.ensure_future(task_zigbee_log_listener())


@app.after_serving
async def shutdown():
    app.is_shutting_down = True
    app.background_task.cancel()


@app.route("/")
async def index():
    return await render_template("index.html")


@app.route("/topN")
async def topN():
    n = int(request.args.get("n", "0"))
    data_sorted = sorted(get_data(), key=cmp_to_key(compare))
    data_sorted = data_sorted[: n if n > 0 else None]
    output_format = get_output_format()
    return await render_template("topN.html", output_format=output_format, data=data_sorted)


@app.route("/stats")
async def stat():
    output_format = get_output_format()
    data = get_data()
    if not data:
        return ""
    max_lqi, med_lqi, min_lqi = calc_min_med_max(sorted([v.lastHopLqi for v in data]))
    max_rssi, med_rssi, min_rssi = calc_min_med_max(sorted([v.lastHopRssi for v in data]))
    date_time_24h_ago = datetime.now() - timedelta(hours=24)
    date_time_7d_ago = datetime.now() - timedelta(days=7)
    last_event_date_time = sorted([v.dateTime for v in data])[-1]
    return await render_template("stats.html", output_format=output_format,
                                 lqi=(min_lqi, med_lqi, max_lqi),
                                 rssi=(min_rssi, med_rssi, max_rssi),
                                 devices_total=len(data),
                                 devices_last_24h=len([v for v in data if v.dateTime > date_time_24h_ago]),
                                 devices_last_7d=len([v for v in data if v.dateTime > date_time_7d_ago]),
                                 last_event_date_time=last_event_date_time.strftime("%Y-%m-%d %H:%M:%S")
                                 )


def calc_min_med_max(values):
    min_v = values[0]
    max_v = values[-1]
    med_v = values[len(values) // 2] if len(values) % 2 else \
        (values[len(values) // 2] + values[len(values) // 2 - 1]) // 2

    return max_v, med_v, min_v


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=app.config.get("PORT", 8080), debug=app.logger.level == logging.DEBUG)
