void call() {
    pipeline {
        agent {
            label params.NODE_LABEL
        }
        options {
            ansiColor('xterm')
            skipDefaultCheckout()
            timestamps()
            timeout(time: 120, unit: 'MINUTES')
        }
        environment {
            GOBIN = "${env.WORKSPACE}/gobin"
        }

        stages {
            stage('Prepare') {
                steps {
                    script {
                        vegautils.commonCleanup()
                    }
                }
            }
            stage('Get devopstools code') {
                steps {
                    script {
                        gitClone(
                            directory: 'devopstools',
                            branch: params.DEVOPSTOOLS_VERSION,
                            vegaUrl: 'devopstools',
                        )
                    }
                }
            }

            stage('Run spammer') {
                steps {
                    script {
                        print params
                    }
                    withDevopstools(
                        command: '''spam orders \
                            --market-id ''' + params.MARKET_ID + ''' \
                            --network "''' + params.NETWORK_NAME + '''" \
                            --threads ''' + params.THREADS + ''' \
                        	--max-price ''' + params.MAX_PRICE + ''' \
                            --thread-rate-limit ''' + params.THREAD_RATE_LIMIT + ''' \
                            --duration ''' + params.DURATION
                    )
                }
            }
        }
    }
}