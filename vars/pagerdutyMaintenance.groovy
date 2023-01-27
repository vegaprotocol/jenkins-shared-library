def call(def turnOn=true, def turnOfflink=null) {
    def pagerdutyCredentials = [] // to be implemented once token is added
    withCredentials([pagerdutyCredentials]) {
        if (turnOn) {
            def minutes = [
                'fairground': 20,
                'devnet1': 20,
                'stagnet1': 20,
                'stagnet3': 20,
            ][env.NET_NAME]

            def serviceId = [
                'fairground': 'PWQRQ4I',
                'devnet1': 'PAB7WJN',
                'stagnet1': 'PZMCQH8',
                'stagnet3': 'PDZ0RWY',
            ][env.NET_NAME]

            def now = new Date()
            def finish = new Date(now.getTime() + (minutes * 60 * 1000))
            // ISO8601 format -> https://community.pagerduty.com/forum/t/date-range-format-for-api/485
            // https://gist.github.com/kdabir/6bfe265d2f3c2f9b438b -> formatting
            def nowString = now.format("yyyy-MM-dd'T'HH:mm:ssZ", TimeZone.getTimeZone('UTC'))
            def finishString = finish.format("yyyy-MM-dd'T'HH:mm:ssZ", TimeZone.getTimeZone('UTC'))
            // api docs -> https://developer.pagerduty.com/api-reference/b3A6Mjc0ODE1OA-create-a-maintenance-window
            def request = [
                "type": "maintenance_window",
                "start_time": nowString,
                "end_time": finishString,
                "description": "Break due to deployment: ${env.JOB_URL}",
                "services": [
                    "id": serviceId,
                    "type": "service_reference"
                ]
            ]

            def response = sh(
                script: """#!/bin/bash -e
                    curl --request POST \
                        --url https://api.pagerduty.com/maintenance_windows \
                        --header 'Accept: application/vnd.pagerduty+json;version=2' \
                        --header 'Authorization: Token token=${env.PAGERDUTY_TOKEN}' \
                        --header 'Content-Type: application/json' \
                        --header 'From: ' \
                        --data ""
                """,
                returnStdout: true,
            ).trim()
            return new groovy.json.JsonSlurperClassic().parseText(response)['maintenance_window']['self']
        }
        else {
            sh """
                curl --request DELETE \
                    --url ${turnOffLink} \
                    --header 'Accept: application/vnd.pagerduty+json;version=2' \
                    --header 'Authorization: Token token=${env.PAGERDUTY_TOKEN}' \
                    --header 'Content-Type: application/json'
            """
        }
    }
}