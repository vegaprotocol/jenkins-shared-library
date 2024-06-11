def call(args=[:]) {
    networkname = args.netName ?: env.NET_NAME
    newDevopsTools = args.newDevopsTools ?: false
    
    withCredentials([
        usernamePassword(credentialsId: vegautils.getVegaCiBotCredentials(), passwordVariable: 'TOKEN', usernameVariable:'USER')
    ]) {
        dir('devopstools') {
            if (newDevopsTools) {
                return sh (
                    script: """GITHUB_TOKEN=""" + TOKEN + """ go run main.go \
                        """ + args.command + """ \
                        --network-file https://raw.githubusercontent.com/vegaprotocol/networks-config-private/main/""" + networkName + """.toml""",
                    returnStdout: args.returnStdout ?: false,
                )    
            }
            return sh (
                script: "go run main.go ${args.command} --network ${networkname} --github-token \${TOKEN}",
                returnStdout: args.returnStdout ?: false,
            )
        }
    }
}