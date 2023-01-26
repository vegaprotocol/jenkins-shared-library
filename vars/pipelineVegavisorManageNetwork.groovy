void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    def statuses = [
        ok: ':white_check_mark:',
        failed: ':red_circle:',
        unknown: ':white_circle:',
    ]

    def stagesHeaders = [
        version: 'version deployed / network restarted',
        delegate: 'self-delegate',
        markets: 'market created',
        bots: 'bots topped up',
    ]

    def stagesStatus = [
        (stagesHeaders.version) : statuses.unknown,
        (stagesHeaders.delegate) : statuses.unknown,
        (stagesHeaders.markets) : statuses.unknown,
        (stagesHeaders.bots) : statuses.unknown,
    ]

    def stagesExtraMessages = [:]

    def parseMessages = { isOk ->
        stagesStatus.each { header, status ->
            if (!stagesExtraMessages[header]) {
                stagesExtraMessages[header] = status == statuses.unknown ? '(not started)' : ''
            }
        }
        def finalStatus = stagesStatus.collect { header, status ->
            "${status} - ${header} ${stagesExtraMessages[header] ?: ''}"
        }.join('\n')
        def duration = currentBuild.durationString - ' and counting'
        def details = "`${env.NET_NAME}` <${env.RUN_DISPLAY_URL}|more>"
        if (isOk) {
            return ":astronaut: ${details} :rocket: (${duration}) \n ${finalStatus}"
        }
        else {
            return ":scream: ${details} :boom: (${duration}) \n ${finalStatus}"
        }
    }

    RELEASE_VERSION = null
    DOCKER_VERSION = null

    ANSIBLE_VARS = ''

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: params.TIMEOUT, unit: 'MINUTES')
            timestamps()
            lock(resource: env.NET_NAME)
            ansiColor('xterm')
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        currentBuild.description = "action: ${params.ACTION}"
                        (RELEASE_VERSION, DOCKER_VERSION) = vegavisorConfigureReleaseVersion(params.RELEASE_VERSION, params.DOCKER_VERSION)
                    }
                }
            }
            stage('Checkout') {
                parallel {
                    stage('vega'){
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            script {
                                gitClone(
                                    directory: 'vega',
                                    branch: params.VEGA_VERSION,
                                    vegaUrl: 'vega',
                                )
                            }
                        }
                    }
                    stage('k8s'){
                        when {
                            expression { DOCKER_VERSION }
                        }
                        steps {
                            script {
                                gitClone(
                                    directory: 'k8s',
                                    branch: 'main',
                                    vegaUrl: 'k8s',
                                )
                            }
                        }
                    }
                    stage('checkpoint-store'){
                        when {
                            expression { params.USE_CHECKPOINT }
                        }
                        steps {
                            script {
                                gitClone(
                                    directory: 'checkpoint-store',
                                    vegaUrl: 'checkpoint-store',
                                    branch: params.CHECKPOINT_STORE_BRANCH)
                            }
                        }
                    }
                    stage('ansible'){
                        steps {
                            script {
                                gitClone(
                                    directory: 'ansible',
                                    vegaUrl: 'ansible',
                                    branch: params.ANSIBLE_BRANCH)
                            }
                        }
                    }
                    stage('networks-internal') {
                        steps {
                            script {
                                gitClone(
                                    directory: 'networks-internal',
                                    vegaUrl: 'networks-internal',
                                    branch: params.NETWORKS_INTERNAL_BRANCH)
                            }
                        }
                    }
                    stage('devopstools') {
                        when {
                            anyOf {
                                expression {
                                    params.CREATE_MARKETS
                                }
                                not {
                                    expression {
                                        params.USE_CHECKPOINT
                                    }
                                }
                            }
                        }
                        steps {
                            gitClone(
                                directory: 'devopstools',
                                vegaUrl: 'devopstools',
                                branch: params.DEVOPSTOOLS_BRANCH)
                            dir ('devopstools') {
                                sh 'go mod download'
                            }
                        }
                    }
                }
            }
            stage('Prepare') {
                parallel {
                    stage('Build vega, data-node, vegawallet and visor') {
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            dir('vega') {
                                sh label: 'Compile', script: """#!/bin/bash -e
                                    go build -v \
                                        -o ../bin/ \
                                        ./cmd/vega \
                                        ./cmd/data-node \
                                        ./cmd/vegawallet \
                                        ./cmd/visor
                                """
                            }
                            dir('bin') {
                                sh label: 'Sanity check: vega', script: '''#!/bin/bash -e
                                    file ./vega
                                    ./vega version
                                '''
                                sh label: 'Sanity check: data-node', script: '''#!/bin/bash -e
                                    file ./data-node
                                    ./data-node version
                                '''
                                sh label: 'Sanity check: vegawallet', script: '''#!/bin/bash -e
                                    file ./vegawallet
                                    ./vegawallet software version
                                '''
                                sh label: 'Sanity check: visor', script: '''#!/bin/bash -e
                                    file ./visor
                                    ./visor --help
                                '''
                            }
                        }
                    }
                    stage('Checkpoint') {
                        when {
                            expression { params.USE_CHECKPOINT }
                        }
                        stages {
                            stage('Prepare scripts') {
                                options { retry(3) }
                                steps {
                                    dir('checkpoint-store/scripts') {
                                        sh '''#!/bin/bash -e
                                            go mod download -x
                                        '''
                                    }
                                }
                            }
                            stage('Download latest checkpoint') {
                                options { retry(3) }
                                steps {
                                    dir('checkpoint-store') {
                                        withCredentials([sshCredentials]) {
                                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                                go run scripts/main.go \
                                                    download-latest \
                                                    --network "${env.NET_NAME}" \
                                                    --ssh-user "\${PSSH_USER}" \
                                                    --ssh-private-keyfile "\${PSSH_KEYFILE}" \
                                                    --vega-home /home/vega/vega_home
                                            """
                                            sh "git add ${env.NET_NAME}/*"
                                        }
                                    }
                                }
                            }
                            stage('Commit changes') {
                                steps {
                                    dir('checkpoint-store') {
                                        script {
                                            def changesToCommit = sh(script:'git diff --cached', returnStdout:true).trim()
                                            if (changesToCommit == '') {
                                                print('No changes to commit')
                                            } else {
                                                sshagent(credentials: ['vega-ci-bot']) {
                                                    sh 'git config --global user.email "vega-ci-bot@vega.xyz"'
                                                    sh 'git config --global user.name "vega-ci-bot"'
                                                    sh "git commit -m 'Automated update of checkpoints'"
                                                    sh 'git pull origin main --rebase'
                                                    sh "git push origin HEAD:main"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            stage('Get latest checkpoint path') {
                                steps {
                                    dir('checkpoint-store') {
                                        script {
                                            env.LATEST_CHECKPOINT_PATH = sh(script: """#!/bin/bash -e
                                                go run scripts/main.go \
                                                    local-latest \
                                                    --network "${env.NET_NAME}"
                                            """, returnStdout:true).trim()
                                        }
                                        print("Latest checkpoint path: ${env.LATEST_CHECKPOINT_PATH}")
                                    }
                                }
                            }
                        }
                    }
                }
            }  // End: Prepare
            stage('Generate genesis') {
                when {
                    expression {
                        params.ACTION != 'stop-network'
                    }
                }
                stages {
                    stage('Prepare scripts') {
                        options {
                            retry(3)
                        }
                        steps {
                            dir('networks-internal/scripts') {
                                sh '''#!/bin/bash -e
                                    go mod download -x
                                '''
                            }
                            dir('ansible/scripts') {
                                sh '''#!/bin/bash -e
                                    go mod download -x
                                '''
                            }
                        }
                    }
                    // TODO: generate vegawallet config toml file
                    stage('Generate new genesis') {
                        environment {
                            CHECKPOINT_ARG = "${params.USE_CHECKPOINT ? '--checkpoint "' + env.LATEST_CHECKPOINT_PATH + '"' : ' '}"
                        }
                        options { retry(3) }
                        steps {
                            dir('ansible') {
                                script {
                                    env.VALIDATOR_IDS = sh(script:"""
                                        go run scripts/main.go \
                                            get-validator-ids \
                                            --network "${env.NET_NAME}"
                                    """, returnStdout:true).trim()
                                }
                            }
                            withCredentials([
                                usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
                            ]) {
                                dir('networks-internal') {
                                    sh label: 'Generate genesis', script: """#!/bin/bash -e
                                        go run scripts/main.go \
                                            generate-genesis \
                                            --network "${env.NET_NAME}" \
                                            --validator-ids "${env.VALIDATOR_IDS}" \
                                            --github-token "${env.TOKEN}" \
                                            ${env.CHECKPOINT_ARG}
                                    """
                                    sh "git add ${env.NET_NAME}/*"
                                }
                            }
                        }
                    }
                    stage('Commit changes') {
                        steps {
                            dir('networks-internal') {
                                script {
                                    sshagent(credentials: ['vega-ci-bot']) {
                                        sh 'git config --global user.email "vega-ci-bot@vega.xyz"'
                                        sh 'git config --global user.name "vega-ci-bot"'
                                        sh "git commit -m 'Automated update of genesis for ${env.NET_NAME}'"
                                        sh 'git pull origin main --rebase'
                                        sh "git push origin HEAD:main"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage('Ansible') {
                when {
                    expression { env.ANSIBLE_LIMIT }
                }
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    script {
                        if (params.VEGA_VERSION) {
                            sh label: 'copy binaries to ansible', script: """#!/bin/bash -e
                                cp ./bin/vega ./ansible/roles/barenode/files/bin/
                                cp ./bin/data-node ./ansible/roles/barenode/files/bin/
                                cp ./bin/visor ./ansible/roles/barenode/files/bin/
                            """
                        }
                        // create json with function instead of manual
                        ANSIBLE_VARS = writeJSON(
                            returnText: true,
                            json: [
                                release_version: RELEASE_VERSION,
                                unsafe_reset_all: params.UNSAFE_RESET_ALL,
                            ].findAll{ key, value -> value != null }
                        )
                        dir('ansible') {
                            withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                                withCredentials([sshCredentials]) {
                                    if (params.ACTION == 'create-network' && !params.SKIP_INFRA_PROVISION) {
                                        stage('Provision Infrastructure') {
                                            sh label: "ansible playbooks/playbook-barenode-common.yaml", script: """#!/bin/bash -e
                                                ansible-playbook \
                                                    --diff \
                                                    -u "\${PSSH_USER}" \
                                                    --private-key "\${PSSH_KEYFILE}" \
                                                    --inventory inventories \
                                                    --limit "${env.ANSIBLE_LIMIT}" \
                                                    playbooks/playbook-barenode-common.yaml
                                            """
                                        }
                                    }

                                    def stageName = params.ACTION.capitalize().replaceAll('-', ' ')
                                    // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                    //        are set by withCredentials wrapper
                                    stage(stageName) {
                                        sh label: "ansible playbooks/${env.ANSIBLE_PLAYBOOK}", script: """#!/bin/bash -e
                                            ansible-playbook \
                                                --diff \
                                                -u "\${PSSH_USER}" \
                                                --private-key "\${PSSH_KEYFILE}" \
                                                --inventory inventories \
                                                --limit "${env.ANSIBLE_LIMIT}" \
                                                --tag "${params.ACTION}" \
                                                --extra-vars '${ANSIBLE_VARS}' \
                                                playbooks/${env.ANSIBLE_PLAYBOOK}
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
                post {
                    success {
                        script {
                            stagesStatus[stagesHeaders.version] = statuses.ok
                            String action = ': restart'
                            if (RELEASE_VERSION) {
                                action = ": deploy `${RELEASE_VERSION}`"
                            }
                            stagesExtraMessages[stagesHeaders.version] = action
                        }
                    }
                    unsuccessful {
                        script {
                            stagesStatus[stagesHeaders.version] = statuses.failed
                            String action = ': restart'
                            if (RELEASE_VERSION) {
                                action = ": deploy `${RELEASE_VERSION}`"
                            }
                            stagesExtraMessages[stagesHeaders.version] = action
                        }
                    }
                }
            }
            stage('Post deployment actions') {
                parallel {
                    stage('Validators self-delegate') {
                        when {
                            allOf {
                                not {
                                    expression {
                                        params.USE_CHECKPOINT
                                    }
                                }
                                expression {
                                    params.ACTION != 'stop-network'
                                }
                            }
                        }
                        steps {
                            sleep 180
                            withDevopstools(
                                command: 'network self-delegate'
                            )
                        }
                        post {
                            success {
                                script {
                                    stagesStatus[stagesHeaders.delegate] = statuses.ok
                                }
                            }
                            unsuccessful {
                                script {
                                    stagesStatus[stagesHeaders.delegate] = statuses.failed
                                }
                            }
                        }
                    }
                    stage('Market actions') {
                        stages {
                            stage('Create markets & provide lp'){
                                when {
                                    allOf {
                                        expression {
                                            params.ACTION != 'stop-network'
                                        }
                                        expression {
                                            params.CREATE_MARKETS
                                        }
                                    }
                                }
                                steps {
                                    sleep 60 // TODO: Add wait for network to replay all of the ethereum events...
                                    withDevopstools(
                                        command: 'market propose --all'
                                    )
                                    sleep 30 * 7
                                    withDevopstools(
                                        command: 'market provide-lp'
                                    )
                                }
                                post {
                                    success {
                                        script {
                                            stagesStatus[stagesHeaders.markets] = statuses.ok
                                        }
                                    }
                                    unsuccessful {
                                        script {
                                            stagesStatus[stagesHeaders.markets] = statuses.failed
                                        }
                                    }
                                }
                            }
                            stage('Top up bots') {
                                when {
                                    allOf {
                                        expression {
                                            params.TOP_UP_BOTS
                                        }
                                        expression {
                                            params.ACTION != 'stop-network'
                                        }
                                    }
                                }
                                steps {
                                    build(
                                        job: "private/Deployments/${env.NET_NAME}/Topup-Bots",
                                        propagate: false,  // don't fail
                                        wait: false, // don't wait
                                    )
                                }
                                post {
                                    success {
                                        script {
                                            stagesStatus[stagesHeaders.bots] = statuses.ok
                                        }
                                    }
                                    unsuccessful {
                                        script {
                                            stagesStatus[stagesHeaders.bots] = statuses.failed
                                        }
                                    }
                                }
                            }
                        }
                    }
                    stage('Update vegawallet service') {
                        when {
                            allOf {
                                expression {
                                    DOCKER_VERSION
                                }
                                expression {
                                    params.ACTION != 'stop-network'
                                }
                            }
                        }
                        steps {
                            script {
                                // ['vegawallet', 'faucet'].each { app ->
                                ['vegawallet'].each { app ->
                                    releaseKubernetesApp(
                                        networkName: env.NET_NAME,
                                        application: app,
                                        directory: 'k8s',
                                        makeCheckout: false,
                                        version: DOCKER_VERSION,
                                        forceRestart: false,
                                        timeout: 5,
                                        wait: false,
                                    )
                                }
                            }
                        }
                    }

                }
            }
        }
        post {
            success {
                slackSend(
                    channel: '#env-deploy',
                    color: 'good',
                    message: parseMessages(true),
                )
            }
            unsuccessful {
                slackSend(
                    channel: '#env-deploy',
                    color: 'danger',
                    message: parseMessages(false),
                )
            }
            always {
                cleanWs()
            }
        }
    }
}
