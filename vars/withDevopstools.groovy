def call(args=[:]) {
    withCredentials([
        usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
    ]) {
        dir('devopstools') {
            return sh (
                script: "go run main.go ${args.command} --network ${env.NET_NAME} --github-token \${TOKEN}",
                returnStdout: args.returnStdout ?: false,
            )
        }
    }
}