/**
 *  Fronius Solar Inverter
 *
 *  Copyright 2015 Tim Flinders
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  EDITED by jchurch to include runEvery15Minutes(refresh) - 02/20
 *  EDITED by jchurch and Markus to include ErrorCode information and clean up ported code for Hubitat slightly - 02/20
 *  EDITED by jchurch and Markus to include kWh conversation for energy stat - 02/21
 *  Updates by HardyM to give 1 min updates, reduce to 30 mins polls overnight, and also return data from Fronius SmartMeter to give Grid power and Load. 2021-07-25
 *  Dec 18,2021.  Added attribute access to pGrid and pLoad. (HardyM)
 *  Jan 22,2024 - Updated to work with latest API (Sébastien Viel & Stephen Townsend)
 *  Oct 24,2026 - Offline backoff (1 min -> 5 min -> 30 min), response validation, single data request,
 *                null-safe parsing, power reset to 0 when offline, network ID set only when it changes
 *
 *  Sourced from here
 *  https://github.com/SebastienViel/FroniusInverter/tree/main
 *
 */

import groovy.json.JsonSlurper
import java.math.BigDecimal

preferences {
	input("inverterNumber", "number", title: "Inverter Number", description: "The Inverter Number", required: true, displayDuringSetup: true)
	input("destIp", "text", title: "IP", description: "The device IP", required: true, displayDuringSetup: true)
	input("destPort", "number", title: "Port", description: "The port you wish to connect", required: true, displayDuringSetup: true)
	input("logDebugEnable", "bool", title: "Enable Debug logging", required: true, defaultValue: false)
}

metadata {
	definition (
		name: "Fronius Solar Inverter",
		namespace: "TimFlinders",
		author: "Tim Flinders",
		importUrl:"https://raw.githubusercontent.com/SebastienViel/FroniusInverter/main/FroniusSolarInverter.groovy"
	) {
	capability "Polling"
	capability "PowerMeter"
	capability "EnergyMeter"
	capability "Actuator"
	capability "Refresh"
	capability "Sensor"
	capability "VoltageMeasurement"

	attribute "errorCode", "number"
	attribute "pGrid", "number"
	attribute "pLoad", "number"
	attribute "TotalEnergy", "number"
	attribute "YearValue", "number"
	attribute "DayValue", "number"
	attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']
	}
}

def initialize() {
	if(logDebugEnable) log.info "Fronius Inverter ${textVersion()} ${textCopyright()}"
	sendEvent(name: "power", value: 0)
	sendEvent(name: "YearValue", value: 0)
	sendEvent(name: "energy", value: 0)
	sendEvent(name: "pGrid", value: 0)
	sendEvent(name: "pLoad", value: 0)
	sendEvent(name: "TotalValue", value: 0)
	sendEvent(name: "healthStatus", value: "unknown")
	state.parseCallCounter = 0
	// updated()
	poll()
}

// parse events into attributes
def parse(String description) {
	def msg = parseLanMessage(description)

	// Ignore anything that is not a clean HTTP 200 with a body
	if (msg?.status != 200 || !msg?.body) {
		if(logDebugEnable) log.debug "Ignoring reply from inverter: status ${msg?.status}"
		return
	}

	def result
	try {
		result = new JsonSlurper().parseText(msg.body)
	} catch (Exception e) {
		log.warn "Invalid JSON received from inverter: ${e.message}"
		return
	}

	def data = result?.Body?.Data

	// Inverter reported an error code
	def errorCode = data?."$inverterNumber"?.ErrorCode
	if(errorCode != null) {
		markOnline()
		sendEvent(name: "errorCode", value: errorCode)
		return
	}

	// Anything without Site data (e.g. a different API reply) is not usable
	def site = data?.Site
	if(site == null) {
		if(logDebugEnable) log.debug "Reply has no Site data: ${result}"
		return
	}

	markOnline()
	sendEvent(name: "errorCode", value: 0)
	if(logDebugEnable) log.debug "Received data from inverter: ${site}"

	// Values can be null (e.g. P_PV at night), so default everything to 0
	int yearValue  = (site.E_Year ?: 0) as int
	int dayValue   = (site.E_Day ?: 0) as int
	int totalValue = (site.E_Total ?: 0) as int
	int pGrid      = (site.P_Grid ?: 0) as int
	int pLoad      = -((site.P_Load ?: 0) as int)
	int power      = (site.P_PV ?: 0) as int

	sendEvent(name: "power", value: power, unit: "W")
	sendEvent(name: "energy", value: dayValue, unit: "Wh")
	sendEvent(name: "eYear", value: yearValue / 1000, unit: "kWH")
	def value = new BigDecimal(totalValue / 1000).setScale(3, BigDecimal.ROUND_HALF_UP)
	sendEvent(name: "TotalEnergy", value: value, unit: "kWH")
	sendEvent(name: "pGrid", value: pGrid, unit: "W")
	sendEvent(name: "pLoad", value: pLoad, unit: "W")
}

// handle commands
def poll() {
	state.parseCallCounter = (state.parseCallCounter ?: 0) + 1
	int n = state.parseCallCounter

	if (n == 3) {
		// ~3 polls with no reply: asleep or unreachable. Back off to 5 minutes.
		markOffline()
		unschedule("refresh")
		schedule('0 */5 * ? * *', "refresh")
	} else if (n == 15) {
		// Still nothing after ~1 hour of 5 minute polls. Back off to 30 minutes.
		unschedule("refresh")
		schedule('0 */30 * ? * *', "refresh")
	}
	callInvertor()
}

def callInvertor() {
	try {
		setNetworkId()
		// Network failures are asynchronous: they show up as hub warnings and parse() is simply never called
		sendHubCommand(new hubitat.device.HubAction(
			'method': 'GET',
			'path': "/solar_api/v1/GetPowerFlowRealtimeData.fcgi",
			'headers': [ HOST: "$destIp:$destPort" ]
		))
	}
	catch (Exception e) {
		log.warn "Could not send request to inverter: ${e.message}"
	}
}

private void markOffline() {
	log.warn "No response from inverter at ${destIp}:${destPort}, polling less often"
	if (device.currentValue("healthStatus") != "offline") {
		sendEvent(name: "healthStatus", value: "offline")
	}
	// Don't leave the last production value showing while the inverter is unreachable
	sendEvent(name: "power", value: 0, unit: "W")
}

private void markOnline() {
	if ((state.parseCallCounter ?: 0) >= 3) {
		log.info "Inverter is responding again, back to 1 minute polling"
		updated()    // restores the 1 minute schedule and resets the counter
	}
	state.parseCallCounter = 0
	if (device.currentValue("healthStatus") != "online") {
		sendEvent(name: "healthStatus", value: "online")
	}
}

private void setNetworkId() {
	if (!destIp || !destPort) return
	String dni = "${convertIPtoHex(destIp)}:${convertPortToHex(destPort)}"
	if (device.deviceNetworkId != dni) {
		device.deviceNetworkId = dni
	}
}

private def textVersion() {
	def text = "Version 1.2"
}

private def textCopyright() {
	def text = "Copyright © 2015 Tim Flinders"
}

private String convertIPtoHex(ipAddress) {
	String hex = ipAddress.tokenize( '.' ).collect {  String.format( '%02X', it.toInteger() ) }.join()
	return hex
}

private String convertPortToHex(port) {
	return String.format( '%04X', port.toInteger() )
}

def refresh(){
	poll()
}

def updated(){
	unschedule("refresh")
	schedule('0 */1 * ? * *', "refresh")
	state.parseCallCounter = 0
	setNetworkId()
}
