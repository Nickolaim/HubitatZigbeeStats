/**
 *
 *   File: ZigbeeStats.groovy
 *
 *  Copyright 2022 Nick M
 *
 *  Description:
 *      Driver that collects information from Zigbee event log and determines worst performing devices
 *
 *  References:
 *     - Repository: https://github.com/Nickolaim/HubitatZigbeeStats
 *
 *   Installation and usage:
 *     - See README in the repository
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
        input name: "listenerUrl", type: "string", title: "URL of site running on listener.py"
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

String readString(StringReader sr) {
    StringBuilder builder = new StringBuilder()
    int ch;
    while ((ch = sr.read()) != -1) {
        builder.append((char) ch)
    }
    return builder.toString()
}


@SuppressWarnings("unused")
void timerCallback() {
    if (listenerUrl == None) {
        logError "Listener URL is not set, nothing to do."
        return
    }

    String urlTopN = "${listenerUrl}/topN?format=tile&n=${topN}"
    logDebug "Request URL: ${urlTopN}"
    httpGet(uri: urlTopN, contentType: "text/plain") { response ->
        response_text = readString(response.data)
        sendEventIfChanged(name: "tileTopN", value: response_text)
    }

    String urlStats = "${listenerUrl}/stats?format=tile"
    logDebug "Request URL: ${urlStats}"
    httpGet(uri: urlStats, contentType: "text/plain") { response ->
        response_text = readString(response.data)
        sendEventIfChanged(name: "tileStats", value: response_text)
    }
}

void sendEventIfChanged(Map evt) {
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange = true
        sendEvent(evt)
    }
}
