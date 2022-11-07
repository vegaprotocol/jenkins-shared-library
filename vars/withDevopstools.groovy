def call(args=[:]) {
    networkname = args.netName ?: env.NET_NAME
    withCredentials([
        usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
    ]) {
        dir('devopstools') {
            return sh (
                script: "go run main.go ${args.command} --network ${networkname} --github-token \${TOKEN}",
                returnStdout: args.returnStdout ?: false,
            )
        }
    }
}