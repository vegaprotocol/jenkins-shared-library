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
    String credentialsId = config.get('credentialsId', vegautils.getVegaCiBotCredentials())
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
    Map prData = getData(config + ['prFields': ['body', 'comments', 'reviews']])
    result += prData.body
    prData.comments.each { commentData ->
        result += commentData.body
    }
    prData.reviews.each { reviewData ->
        result += reviewData.body
    }
    result.removeAll([''])
    return result
}

//
// Parse all comments of a PR to find
//   information about connected changes in other repos
//
Map getConnectedChangesInOtherRepos(Map config = [:]) {
    Map result = [:]
    List<String> allComments = getAllComments(config)

    Map commentStringToParam = [
        'vega': 'VEGA_BRANCH',
        'vegatools': 'VEGATOOLS_BRANCH',
        'system-tests': 'SYSTEM_TESTS_BRANCH',
        'vega-market-sim': 'VEGA_MARKET_SIM_BRANCH',
        'networks': 'NETWORKS_BRANCH',
        'networks-internal': 'NETWORKS_INTERNAL_BRANCH',
        'checkpoint-store': 'CHECKPOINT_STORE_BRANCH',
        'vegacapsule': 'VEGACAPSULE_BRANCH',
        'jenkins-shared-library': 'JENKINS_SHARED_LIB_BRANCH',
        'deploy-to-devnet': 'DEPLOY_TO_DEVNET',
        'devops-infra': 'DEVOPS_INFRA_BRANCH',
        'devopsscripts': 'DEVOPSSCRIPTS_BRANCH',
        'devopstools': 'DEVOPSTOOLS_BRANCH',
        'vega-version': 'VEGA_VERSION',
        'node-label': 'NODE_LABEL',
        'downstream-node-label': 'DOWNSTREAM_NODE_LABEL',
        'vega-branch-upgrade': 'VEGA_BRANCH_UPGRADE',
        'extra-pytest-args': 'TEST_EXTRA_PYTEST_ARGS',
        'default-product': 'DEFAULT_PRODUCT',
        'network-param-overrides': 'SYSTEM_TESTS_NETWORK_PARAM_OVERRIDES',
        'system-tests-debug': 'SYSTEM_TESTS_DEBUG',
        'timeout': 'TIMEOUT',
        'scenario': 'SCENARIO',
        'run-soak-test': 'RUN_SOAK_TEST',
        'run-protocol-upgrade-proposal': 'RUN_PROTOCOL_UPGRADE_PROPOSAL',
        'print-network-logs': 'PRINT_NETWORK_LOGS',
        'extra-envs': 'EXTRA_ENVS',
    ]

    echo 'To override params in the PR comment, place jenkins according to following relation'
    echo '{'
    commentStringToParam.each {key, value ->
        echo "\"$key\": \"$value\""
    }
    echo '}'

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
                    if (commentStringToParam[repo] && branch instanceof String) {
                        result[commentStringToParam[repo]] = branch
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

def getOriginRepo(def origin) {
    if (env.CHANGE_URL) {
        def prParams = getData(
            url: env.CHANGE_URL,
            prFields:['headRepositoryOwner', 'headRepository']
        )
        echo "prParams = ${prParams}"
        return "${prParams.headRepositoryOwner.login}/${prParams.headRepository.name}"
    } else {
        return origin
    }
}


List<String> getAllLabelsFor(Map config = [:]) {
    List<String> result = []
    Map prData = getData(config + ['prFields': ['labels']])
    prData.labels.each { labelData ->
        result += labelData.name
    }
    result.removeAll([''])
    return result
}

List<String> getAllLabels() {
    if (env.CHANGE_URL) {
        return getAllLabelsFor(url: env.CHANGE_URL)
    }
    return []
}

boolean hasLabelFor(Map config = [:]) {
    List<String> allLabels = getAllLabels(config)
    allLabels.each { label ->
        if (label.toLowerCase() == config.label.toLowerCase()) {
            return true
        }
    }
    return false
}

boolean hasLabel(String label) {
    if (env.CHANGE_URL) {
        return hasLabelFor(label: label, url: env.CHANGE_URL)
    }
    return false
}
