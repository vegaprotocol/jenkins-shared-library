void call(Map config=[:]) {
    if (!config.containsKey('playbookName') || config.playbookName.length() < 2) {
        error("Pass non empty config.playbookName to the pipelinePublicAnsible call")
    }

    if !config.containsKey('hostsLimit') || config.hostsLimit.length() < 2 {
        error("Pass non empty config.hostsLimit to the pipelinePublicAnsible call")
    }

    String playbookName = config.playbookName
    String hostsLimit = config.hostsLimit

    Map<String, String> extraVariables = config.extraVariables ?: [:]
    String ansibleTestnetAutomationBranch = config.ansibleTestnetAutomationBranch ?: 'main'
    String networksConfigPrivateBranch = config.networksConfigPrivateBranch ?: 'main'
    Bool withGitClone = config.withGitClone ?: false
    Bool applyChanges = config.applyChanges ?: false

    List<String> ansibleFalgs = [
        "--diff",
        '--limit "' + hostsLimit + '"',
    ]

    if (!applyChanges) {
        ansibleFalgs << "--check"
    }

    if (extraVariables.length() > 0) {
        String extraFlagsEncoded = writeJSON(
            returnText: true,
            json: extraVariables
        )

        ansibleFalgs << "--extra-flags '"+ extraFlagsEncoded +"'"
    }

    if (withGitClone) {
        gitClone(
            directory: 'ansible-testnet-automation',
            vegaUrl: 'ansible-testnet-automation',
            branch: ansibleTestnetAutomationBranch,
            extensions: [[$class: 'LocalBranch', localBranch: "**" ]]
        )
        gitClone(
            directory: 'networks-config-private',
            vegaUrl: 'networks-config-private',
            branch: networksConfigPrivateBranch,
            extensions: [[$class: 'LocalBranch', localBranch: "**" ]]
        )
    }

    dir('networks-config-private/ansible') {
        sh '''
        ansible-playbook playbooks/''' + playbookName + '''.yaml
        '''
    }

            
}