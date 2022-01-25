import groovy.json.JsonSlurperClassic

//
// Get PR's data
//
Map getData(Map config = [:]) {
    // optional PR number or branch name
    // if empty - use current branch
    String pr = config.get('pr', '')
    String branch = config.get('branch', '')
    String url = config.get('url', '')
    // optional `OWNER/REPO`
    // if empty - use current repo or from `url`
    String repo = config.get('repo', '')
    // optional GH CLI credentials
    String credentialsId = config.get('credentialsId', 'github-vega-ci-bot-artifacts')
    // List of fields to get for a PR
    List<String> prFields = config.get('prFields', ['body'])

    String repoArgument = repo ? "--repo '${repo}'" : ''
    String prOrBranchOrURL = pr ?: ( branch ?: url )

    String cliResult = null

    withGHCLI('credentialsId': credentialsId) {
        cliResult = sh(
            label: "Get data for a PR: ${prOrBranchOrURL}",
            returnStdout: true,
            script: "gh pr ${repoArgument} view ${prOrBranchOrURL} --json ${prFields.join(',')}"
        ).trim()

        echo "GH CLI result: ${cliResult}"
    }

    return new JsonSlurperClassic().parseText(cliResult)
}

String getFirstComment(Map config = [:]) {
    Map prData = getData(config + ['prFields': ['body']])
    return prData.body
}

List<String> getAllComments(Map config = [:]) {
    List<String> result = []
    Map prData = getData(config + ['prFields': ['body', 'comments']])
    result += prData.body
    prData.comments.each { commentData ->
        result += commentData.body
    }
    return result
}

//
// Parse all comments of a PR to find
//   information about connected changes in other repos
//
Map getConnectedChangesInOtherRepos(Map config = [:]) {
    Map result = [:]
    List<String> allComments = getAllComments(config)

    Map repoToParam = [
        'vega': 'VEGA_CORE_BRANCH',
        'data-node': 'DATA_NODE_BRANCH',
        'vegawallet': 'VEGAWALLET_BRANCH',
        'ethereum-event-forwarder': 'ETHEREUM_EVENT_FORWARDER_BRANCH',
        'devops-infra': 'DEVOPS_INFRA_BRANCH',
        'vegatools': 'VEGATOOLS_BRANCH',
        'system-tests': 'SYSTEM_TESTS_BRANCH',
        'protos': 'PROTOS_BRANCH',
        'networks': 'NETWORKS_BRANCH',
        'checkpoint-store': 'CHECKPOINT_STORE_BRANCH'
    ]

    // For every COMMENT
    allComments.each { comment ->
        comment = comment.replaceAll("\\r", "");

        // Find every CODE section in a Comment
        (comment =~ /(?i)(?s)```(.*?)```/).findAll().each { codeSection ->
            String content = codeSection[1]  // first regex match
            content = content - ~/^json/  // remove json from the code start
            content = content.replaceAll(/\/\/.*\n/, "")  // remove all comments
            // TODO: remove echo at some point in the future. Keep it for now for debug
            echo "Parsing content: ### Begin ###\n${content}\n### End ###"
            try {
                Map contentJSON = new JsonSlurperClassic().parseText(content)
                // TODO: remove echo at some point in the future. Keep it for now for debug
                echo "Parsed"
                // For each top level key-value
                contentJSON.each { repo, branch ->
                    if (repoToParam[repo] && branch instanceof String) {
                        result[repoToParam[repo]] = branch
                    }
                }
            } catch (Exception e) {
                // TODO: remove echo at some point in the future. Keep it for now for debug
                echo "Not json. ${e}"
            }
        }
    }

    return result
}

Map injectPRParams() {
    if (env.CHANGE_URL) {
        Map customParams = getConnectedChangesInOtherRepos(url: env.CHANGE_URL)
        return params + customParams  // merge dictionaries
    }
    return params
}
