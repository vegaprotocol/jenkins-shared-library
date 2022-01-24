import groovy.json.JsonSlurperClassic

//
// Get First/Body commnet of a PR
//
String getBody(Map config = [:]) {
    // optional PR number or branch name
    // if empty - use current branch
    String pr = config.get('pr', '')
    String branch = config.get('branch', '')
    // optional `OWNER/REPO`
    // if empty - use current repo
    String repo = config.get('repo', '')
    // optional GH CLI credentials
    String credentialsId = config.get('credentialsId', 'github-vega-ci-bot-artifacts')

    String repoArgument = repo ? "--repo '${repo}'" : ''
    String prOrBranch = pr ?: branch

    String cliResult = null

    withGHCLI('credentialsId': credentialsId) {
        cliResult = sh(
            label: "Get first/body comment of a PR: ${prOrBranch}",
            returnStdout: true,
            script: "gh pr ${repoArgument} view ${prOrBranch} --json body"
        ).trim()

        echo "GH CLI result: ${cliResult}"
    }

    Map cliResultJSON = new JsonSlurperClassic().parseText(cliResult)
    return cliResultJSON.body
}

//
// Parse First/Body comment of a PR to find
//   information about connected changes in other repos
//
Map getConnectedChangesInOtherRepos(Map config = [:]) {
    Map result = [:]
    String body = getBody(config)

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

    body = body.replaceAll("\\r", "");

    (body =~ /(?i)(?s)```(.*?)```/).findAll().each { item ->
        String content = item[1]
        content = content - ~/^json/
        echo "Parsing content: ### Begin ###\n${content}\n### End ###"
        try {
            Map contentJSON = new JsonSlurperClassic().parseText(content)
            echo "Parsed"
            contentJSON.each { repo, branch ->
                if (repoToParam[repo]) {
                    result[repoToParam[repo]] = branch
                }
            }
        } catch (Exception e) {
            echo "Not json. ${e}"
        }
    }

    return result
}
