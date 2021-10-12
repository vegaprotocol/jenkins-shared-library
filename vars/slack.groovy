/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable CouldBeSwitchStatement */

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

void slackSendCIUnstable(Map config) {
    String slackChannel = config.get('channel', '#tradingcore-notify')
    String message = composeMessage(config)

    slackSend(
        channel: slackChannel,
        color: 'warning',
        message: ":large_orange_circle: ${message}",
    )
}

void slackSendCIAborted(Map config) {
    String slackChannel = config.get('channel', '#tradingcore-notify')
    String message = composeMessage(config)

    slackSend(
        channel: slackChannel,
        color: '#000000',
        message: ":black_circle: ${message}",
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
    String currentResult = currentBuild.result ?: currentBuild.currentResult
    echo "result=${currentBuild.result}, currentResult=${currentBuild.currentResult}"
    if (currentResult == 'UNSTABLE') {
        slackSendCIUnstable(config)
    } else if (currentResult == 'ABORTED') {
        slackSendCIAborted(config)
    } else if (currentResult == 'SUCCESS') {
        slackSendCISuccess(config)
    } else {
        slackSendCIFailure(config)
    }
}

void slackSendDeployStatus(Map config) {
    String network = config.network
    String version = config.version
    Boolean restart = config.get('restart', false)
    String slackChannel = config.get('channel', '#env-deploy')
    String jobURL = config.get('jobURL', env.RUN_DISPLAY_URL)

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    if (version) {
        msg = "deploy `${version}` to `${network}`"
    } else if (restart) {
        msg = "restart `${network}`"
    } else {
        msg = "apply changes to `${network}`"
    }

    if (currentResult == 'SUCCESS') {
        msg = ":rocket: Successful ${msg} :astronaut:"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: Aborted to ${msg}. See details in <${jobURL}|Jenkins>"
        color = '#000000'
    } else {
        msg = ":boom: Failed to ${msg}. @ops see details in <${jobURL}|Jenkins> :scream:"
        color = 'good'
    }

    msg += " (${duration})"

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
