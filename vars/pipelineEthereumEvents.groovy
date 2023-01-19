// https://github.com/vegaprotocol/jenkins-shared-library/issues/373
def call() {
    settings = [
        sandbox: [
            party: '1a94865a473e547c3e284f266af627856680cadf29fb52aecb3bf72ffff447ad',
            erc20: '0x51d9Dbe9a724C6a8383016FaD566e55c95359D36',
            amount: '0.0001',
        ]
    ]
    pipeline {
        agent any
        options {
            timestamps()
            ansiColor('xterm')
            lock(resource: env.NET_NAME)
        }
        stages {
            stage('Checkout') {
                steps {
                    gitClone(
                        vegaUrl: 'devopstools',
                        directory: 'devopstools',
                        branch: params.DEVOPSTOOLS_BRANCH,
                    )
                    dir ('devopstools') {
                        sh 'go mod download'
                    }
                }
            }
            stage('Send ethereum events') {
                steps {
                    withDevopstools(
                        command: "topup parties --parties ${settings[env.NET_NAME].party} --amount ${settings[env.NET_NAME].amount} --erc20-token-address ${settings[env.NET_NAME].erc20} --repeat ${params.NUMBER_OF_EVENTS}"
                    )
                }
            }
        }
    }
}