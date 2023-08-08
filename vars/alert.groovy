import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder

//
// Disable all alerts for a single machine or entire vega network.
// Arguments:
//  - node - node name to disable alerts for, e.g. `n00.devnet1.vega.rocks`
//  - environment - environment name to disable alerts for, e.g. `fairground`
//  - duration (optional) - duration in minutes for how long the alerts will be disabled for, default: 20
//
String disableAlerts(Map args=[:]) {
    String matcherName = ""
    String matcherValue = ""

    if (args?.node) {
        matcherName = "instance"
        matcherValue = args.node
    } else if (args?.environment) {
        matcherName = "environment"
        matcherValue = args.environment
    } else {
        throw "disableAlerts error: need to provide 'node' or 'environment' argument. Provided: ${args}"
    }

    int duration = (args?.duration ?: "20") as int // in minutes
    def start = new Date()
    def end = new Date(start.getTime() + (duration * 60 * 1000))

    String strStart = start.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
    String strEnd = end.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
    String strResponse

    withCredentials([
        usernamePassword(credentialsId: 'prom-basic-auth', usernameVariable:'PROM_LOGIN', passwordVariable: 'PROM_PASSWORD')
    ]) {
        strResponse = sh(label: "HTTP Prometheus API: create silence",
            returnStdout: true,
            script: """#!/bin/bash -e
                curl -X POST \
                    https://prom.ops.vega.xyz/alertmanager/api/v2/silences \
                    -H 'Content-Type: application/json' \
                    -u "\${PROM_LOGIN}:\${PROM_PASSWORD}" \
                    -d '{
                        "matchers": [
                        {
                            "name": "${matcherName}",
                            "value": "${matcherValue}",
                            "isRegex": false,
                            "isEqual": true
                        }
                        ],
                        "startsAt": "${strStart}",
                        "endsAt": "${strEnd}",
                        "createdBy": "Jenkins",
                        "comment": "Created by Jenkins pipeline: ${env.RUN_DISPLAY_URL}",
                        "id": null
                    }'
            """
        ).trim()
    }

    print("disableAlerts response: ${strResponse}")

    def response = new groovy.json.JsonSlurperClassic().parseText(strResponse)
    def silenceID = response["silenceID"]
    assert silenceID: "silenceID is missing in the response from Prometheus"

    print("Disabled alerts for ${matcherName}=${matcherValue} for ${duration} minutes, until: ${strEnd} UTC. Prometheus Silence ID: ${silenceID}")

    return silenceID
}

//
// Re-enable alerts that got disabled.
// Arguments:
//  - silenceID (required) - the id of the alerts disablement,
//  - delay (optional) - the number of minutes after which the alerts will be enabled. Must be > 0, default: 5
//
void enableAlerts(Map args=[:]) {
    assert args?.silenceID : "enableAlerts error: missing silenceID argument. Arguments: ${args}"
    int delay = (args?.delay ?: "5") as int // in minutes
    assert delay > 0 : "delay cannot be zero"

    def now = new Date()
    def end = new Date(now.getTime() + (delay * 60 * 1000))
    String strEnd = end.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))

    silenceConfig = getDisabledAlerts(silenceID: args.silenceID)
    assert silenceConfig : "Alert config for ${args.silenceID} cannot be empty."
    silenceConfig["endsAt"] = strEnd
    String matcherName = silenceConfig["matchers"][0]["name"]
    String matcherValue = silenceConfig["matchers"][0]["value"]

    String postData = new JsonBuilder(silenceConfig).toPrettyString()

    withCredentials([
        usernamePassword(credentialsId: 'prom-basic-auth', usernameVariable:'PROM_LOGIN', passwordVariable: 'PROM_PASSWORD')
    ]) {
        sh label: 'HTTP Prometheus API: delete silence', script: """#!/bin/bash -e
            curl -X POST \
                https://prom.ops.vega.xyz/alertmanager/api/v2/silences \
                -H 'Content-Type: application/json' \
                -u "\${PROM_LOGIN}:\${PROM_PASSWORD}" \
                -d '${postData}'
        """
    }

    print("Alerts ${matcherName}=${matcherValue} will be enabled in ${delay} minutes, at ${strEnd} UTC. Prometheus Silence ID: ${args.silenceID}")
}

//
// Get information/config of alert disablement.
// Arguments:
//  - silenceID (required) - the id of the alerts disablement,
//
Object getDisabledAlerts(Map args=[:]) {
    assert args?.silenceID : "getDisabledAlerts error: missing silenceID argument. Arguments: ${args}"

    String strResponse

    withCredentials([
        usernamePassword(credentialsId: 'prom-basic-auth', usernameVariable:'PROM_LOGIN', passwordVariable: 'PROM_PASSWORD')
    ]) {
        strResponse = sh(label: "HTTP Prometheus API: get silence",
            returnStdout: true,
            script: """#!/bin/bash -e
                curl -X GET \
                    https://prom.ops.vega.xyz/alertmanager/api/v2/silence/${args.silenceID} \
                    -u "\${PROM_LOGIN}:\${PROM_PASSWORD}"
            """
        ).trim()
    }

    print("getDisabledAlerts response: ${strResponse}")

    def response = new groovy.json.JsonSlurperClassic().parseText(strResponse)
    return response
}
