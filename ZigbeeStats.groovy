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
 *     - Paste the source code as a new driver
 *     - Create a new virtual device with the driver
 *     - Adjust preferences if needed
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

static Long connectTimeoutMilliseconds() { 120_000 }

static Long webSocketWatchdogLookBackMilliseconds() { 600_000 }

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
        input name: "webSocketUrl", type: "string", title: "URL to `/zigbeeLogsocket` event stream, " +
                "note it should be on the secondary port", defaultValue: "ws://127.0.0.1:8080/zigbeeLogsocket"
    }
}

void logDebug(msg) {
    if (enableLog) {
        log.debug msg
    }
}

@SuppressWarnings("unused")
def installed() {
    initialize()
}

@SuppressWarnings("unused")
def updated() {
    initialize()
}

def initialize() {
    logDebug "initialize()"
    state.lastConnectTime = 0
    state.zigbeeEntries = [:]
    state.lastMessageTime = 0
    if (state.webSocketWatchdogScheduled == null) {
        runEvery30Minutes(webSocketWatchdog)
        state.webSocketWatchdogScheduled = true
    }
    sendEventIfChanged(name: "tileTopN", value: "")
    sendEventIfChanged(name: "tileStats", value: "")
    webSocketConnect()
}

void webSocketConnect() {
    logDebug "webSocketConnect()"
    if ((now() - state.lastConnectTime) < connectTimeoutMilliseconds()) {

        //noinspection GroovyAssignabilityCheck
        def runTime = new Date(now() + connectTimeoutMilliseconds())
        logDebug "Scheduling next socket connection to: ${runTime}"

        runOnce(runTime, "webSocketConnect")
        return
    }

    logDebug "wsUrl: ${webSocketUrl}"
    try {
        interfaces.webSocket.connect(webSocketUrl)
        state.lastConnectTime = now()
        logDebug "Connection has been established"
    }
    catch (e) {
        logDebug "Initialize error: ${e.message} ${e}"
        log.error "Web socket connect failed: ${e}"
    }
}

@SuppressWarnings("unused")
void webSocketWatchdog() {
    logDebug "webSocketWatchdog()"
    if ((now() - state.lastMessageTime) < webSocketWatchdogLookBackMilliseconds()) {
        logDebug "webSocketWatchdog() has no work to do - the last message received recently"
        return
    }
    webSocketConnect()
}

@SuppressWarnings("unused")
void webSocketStatus(final String status) {
    logDebug "webSocketStatus() with status: ${status}"

    if (status.startsWith("failure: ")) {
        log.warn("Failure message from web socket: ${status.substring("failure: ".length())}")
        webSocketConnect()
    }
}

@SuppressWarnings("unused")
def parse(String message) {
    logDebug "parse() with message: ${message}"

    def json
    try {
        json = parseJson(message)
    } catch (Exception e) {
        log.error("Error parsing json: ${e}\ninput message: ${message}")
        return
    }
    if (json.id == 0) {
        return  // No need to add hub as a device
    }

    //noinspection GroovyAssignabilityCheck
    final String deviceId = Integer.toString(json.id)
    final String deviceName = json.name
    final int lastHopLqi = json.lastHopLqi
    final int lastHopRssi = json.lastHopRssi

    def entry = ["name": deviceName, "id": deviceId, "lastHopLqi": lastHopLqi, "lastHopRssi": lastHopRssi]

    state.zigbeeEntries[deviceId] = entry

    def entries = state.zigbeeEntries.values().toArray()

    sbTopN = composeTileTopNText(entries)
    sendEventIfChanged(name: "tileTopN", value: sbTopN.toString())

    sbStats = composeTileStatsText(entries)
    sendEventIfChanged(name: "tileStats", value: sbStats.toString())

    state.lastMessageTime = now()
}

StringBuilder composeTileTopNText(entries) {
    StringBuilder result = new StringBuilder("""
<table width="100%" valign="top">
<thead>
<tr>
<th>ZigBee ID</th>
<th>Name</th>
<th>Last Hop LQI</th>
<th>Last Hop RSSI</th>
</tr>
</thead>
<tbody>
""")

    def sorted_entries = entries.sort({
        entryA, entryB ->
            if (entryA.lastHopLqi == entryB.lastHopLqi) {
                return entryA.lastHopRssi - entryB.lastHopRssi
            }
            return entryA.lastHopLqi - entryB.lastHopLqi
    } as Comparator
    )

    if (sorted_entries.length > 0) {
        //noinspection GroovyAssignabilityCheck
        Integer last = Math.min(topN.toInteger(), sorted_entries.length) - 1
        //noinspection GroovyAssignabilityCheck
        sorted_entries[0..last].each { val ->
            result.append(
                    """
<tr>
<td>${hubitat.helper.HexUtils.integerToHexString(val.id.toInteger(), 2)}</td>
<td style="align=left">${val.name}</td>
<td>${val.lastHopLqi}</td>
<td>${val.lastHopRssi}</td>
</tr>
""")
        }
    }
    result.append("""
</tbody>
</table>
""")
    return result
}

static StringBuilder composeTileStatsText(entries) {
    StringBuilder result = new StringBuilder()
    def l = entries.length
    result.append("Devices reported: ${l}<div/>")
    if (l == 0) {
        return result
    }

    def rssi = new Integer[l]
    entries.eachWithIndex { entry, i ->
        //noinspection GroovyAssignabilityCheck
        rssi[i] = entry.lastHopRssi
    }
    def sorted_rssi = rssi.sort()
    def rssi_min = sorted_rssi[0]
    def rssi_max = sorted_rssi[sorted_rssi.length - 1]
    result.append("RSSI Min: ${rssi_min}<div/>")
    result.append("RSSI Max: ${rssi_max}<div/>")

    return result
}

void sendEventIfChanged(Map evt) {
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange = true
        sendEvent(evt)
    }
}
