def call(args=[:]) {
    networkname = args.netName ?: env.NET_NAME
    withCredentials([
        usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
    ]) {
        dir('devopstools') {
            if (args.returnStdout) {
                sh (
                    script: "go run main.go ${args.command} --network ${networkname} --github-token \${TOKEN} > stdout.txt",
                )
                def stdout = readFile(
                    file: 'stdout.txt'
                )
                echo "${stdout}"
                return stdout
            }
            else {
                return sh (
                    script: "go run main.go ${args.command} --network ${networkname} --github-token \${TOKEN}",
                )
            }
        }
    }
}