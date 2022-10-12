def call(args=[:]) {
    withCredentials([
        usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
    ]) {
        dir('devopstools') {
            sh "go run main.go ${args.command} --network ${env.NET_NAME} --github-token \${TOKEN}"
        }
    }
}