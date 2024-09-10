void call(Map config=[:]) {
    if (!config.containsKey('playbookName') || config.playbookName.length() < 2) {
        error("Pass non empty config.playbookName to the pipelinePublicAnsible call")
    }

    if (!config.containsKey('hostsLimit') || config.hostsLimit.length() < 2) {
        error("Pass non empty config.hostsLimit to the pipelinePublicAnsible call")
    }

    String playbookName = config.playbookName
    String hostsLimit = config.hostsLimit

    Map<String, String> extraVariables = config.extraVariables ?: [:]
    String ansibleTestnetAutomationBranch = config.ansibleTestnetAutomationBranch ?: 'main'
    String networksConfigPrivateBranch = config.networksConfigPrivateBranch ?: 'main'

    Boolean withGitClone = config.withGitClone ?: false
    Boolean dryRun = config.dryRun ?: false

    String sshSecretName = config.sshSecretName ?: 'ssh-vega-network'

    List<String> ansibleFalgs = [
        "--diff",
        '--limit "' + hostsLimit + '"',
    ]

    if (dryRun) {
        ansibleFalgs << "--check"
    }

    if (extraVariables.size() > 0) {
        String extraFlagsEncoded = writeJSON(
            returnText: true,
            json: extraVariables
        )

        ansibleFalgs << "--extra-vars '"+ extraFlagsEncoded +"'"
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

    String ansibleFlagsCmd = ansibleFalgs.join(' ')

    withCredentials([
        sshUserPrivateKey(
            credentialsId: sshSecretName,
            keyFileVariable: 'PSSH_KEYFILE',
            usernameVariable: 'PSSH_USER',
        ),
    ]) {
        dir('networks-config-private/ansible') {
            sh '''
            ansible-playbook \
            -u ''' + PSSH_USER + ''' \
            --private-key ''' + PSSH_KEYFILE + ''' \
            playbooks/''' + playbookName + '''.yaml ''' + ansibleFlagsCmd
        }
    }
}