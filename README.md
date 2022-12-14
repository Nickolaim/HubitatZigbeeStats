# HubitatZigbeeStats

Hubitat driver and a Python script for collecting statistics from Zigbee devices joined the hub.
Helps with understanding what is happening with the Zigbee network, how good/bad connectivity is.

The Python script connects to [Zigbee logs on the hub](https://docs2.hubitat.com/en/user-interface/settings/zigbee-logs). It listens for events, and stores them.
Hubitat driver reads statistics generated by the python script.  Since there is a limit on attribute size in hubitat (1024 characters), `/topN` version for the tile has a limit on how many devices to return. 

## Installation

### Python web service in a docker container
1. `docker pull nick0987/zigbee-stats:latest`
2. Run the image with the following options  
  * Mount `config.toml` to `/app/config.toml`
  * Mount `data.json` to `/app/data.json`
  * Expose container HTTP port 8080 to a local port
3. Verify that it works by navigating to `http://<docker-host>:<local-port>`

### Python web service standalone
Required: python 3.8+
1. Create a virtual environment, e.g. by running `python3 -m venv venv`
2. Activate the virtual environment, e.g. by running `. venv/bin/activate`
3. Install the requirements, e.g. by running `python3 -m pip install -r requirements.txt`
4. Copy `config-template.toml` to `config.toml` and update the settings
5. Run as a script with `python3 ./listener.py`
6. Verify that it works by navigating to `http://<ip-address>:<port-from-settings>`

### Hubitat driver
1. Go to `Drivers code`
2. Press on `New Driver`
3. Past the code from `ZigbeeStats.groovy`.  Press `Save`
4. Go to `Devices`
5. Add a new virtual device with `Type` set to `User/ZigbeeStats`
6. Configure `URL of site running on listener.py` parameter. Use URL from web service steps 
7. Press `Save`
8. Enable the device on a dashboard
9. Add attributes `tileStats` and `tileTopN` as tiles on the dashboard


### Example how data looks on a dashboard
<img src="dashboard.png" alt="Dashboard sample">
