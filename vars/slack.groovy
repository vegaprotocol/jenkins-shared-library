/* groovylint-disable DuplicateStringLiteral */

String composeMessage(Map config) {
        String name = config.get('name', 'Unknown CI')
        String jobURL = config.get('jobURL', env.RUN_DISPLAY_URL)
        String branch = config.get('branch', env.BRANCH_NAME)
        String prURL = config.get('pr', env.CHANGE_URL)
        String prID = config.get('prID', env.CHANGE_ID)
        String message = " ${name} » <${jobURL}|Jenkins ${branch} Job>"
        if (prURL != null) {
            message += " » <${prURL}|GitHub PR #${prID}>"
        }
        message += " (${currentBuild.durationString - ' and counting'})"
        return message
}

void slackSendCISuccess(Map config) {
    String slackChannel = config.get('channel', '#tradingcore-notify')
    String message = composeMessage(config)

    slackSend(
        channel: slackChannel,
        color: 'good',
        message: ":white_check_mark: ${message}",
    )
}

void slackSendCIFailure(Map config) {
    String slackChannel = config.get('channel', '#tradingcore-notify')
    String message = composeMessage(config)

    slackSend(
        channel: slackChannel,
        color: 'danger',
        message: ":red_circle: ${message}",
    )
}

void slackSendCIStatus(Map config) {
    String currentResult = currentBuild.result ?: 'SUCCESS'
    if (currentResult == 'SUCCESS') {
        slackSendCISuccess(config)
    } else {
        slackSendCIFailure(config)
    }
}
