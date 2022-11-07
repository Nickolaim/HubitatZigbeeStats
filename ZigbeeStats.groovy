/**
 *
 *   File: ZigbeeStats.groovy
 *
 *  Copyright 2022 Nick M
 *
 *  Description:
 *      Driver that collects information from Zigbee event log and determines worst performing devices
 *
 *  To use:
 *     - <TBD how to run generator>
 *     - Paste the source code as a new driver
 *     - Create a new virtual device with the driver
 *     - Adjust the preferences if needed
 *
 *  References:
 *     - Repository: https://github.com/Nickolaim/HubitatZigbeeStats
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

metadata {
    definition(name: "ZigbeeStats", namespace: "nickolaim", author: "Nick M",
            importUrl: "https://raw.githubusercontent.com/Nickolaim/HubitatZigbeeStats/main/ZigbeeStats.groovy") {
        capability "Initialize"

        attribute "tileTopN", "string"
        attribute "tileStats", "string"
    }

    preferences {
        input name: "enableLog", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "topN", type: "int", title: "Top N items to generate on topN tile", defaultValue: 5
        input name: "listenerUrl", type: "string", title: ""
    }
}

void logDebug(String msg) {
    if (enableLog) {
        log.debug msg
    }
}

void logError(String msg) {
    log.error msg
}

@SuppressWarnings("unused")
void installed() {
    initialize()
}

@SuppressWarnings("unused")
void uninstalled() {
    logDebug "uninstalled()"
    unschedule()
}

@SuppressWarnings("unused")
void updated() {
    initialize()
}

void initialize() {
    logDebug "initialize()"
    unschedule()
    runEvery5Minutes("timerCallback")

    sendEventIfChanged(name: "tileTopN", value: "")
    sendEventIfChanged(name: "tileStats", value: "")

    // Call for debugging
    timerCallback()
}

@SuppressWarnings("unused")
void timerCallback() {
    if (listenerUrl == None) {
        logError "Listener URL is not set, nothing to do."
        return
    }

    String url = "${listenerUrl}/topN"
    String queryString = "format=tile&n=${topN}"
    httpGet(uri: url, queryString: queryString, contentType: "text/html") { response ->
        if (response.getStatus() > 400) {
            logError "Request to ${url}/${queryString} retuned status code ${response.getStatus()}\n" +
                    "Data: ${response.getData()}"
            return
        }

        logDebug "Received data:"
        logDebug response.data
        sendEventIfChanged(name: "tileTopN", value: response.data)
    }
}

void sendEventIfChanged(Map evt) {
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange = true
        sendEvent(evt)
    }
}
