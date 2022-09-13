
String getPublicIP() {
    def commands = [
      'curl -4 icanhazip.com',
      'curl ifconfig.co',
      'curl ipinfo.io/ip',
      'curl api.ipify.org',
      'dig +short myip.opendns.com @resolver1.opendns.com',
      'curl ident.me',
      'curl ipecho.net/plain'
    ]

    for (it in commands) {
      try {
        boxIp = sh(script: it, returnStdout:true).trim()

        if (boxIp != "") {
          return boxIp;
        }
      } catch(err) {
        // TODO: Add fallback to other services or linux commands
        print("Cannot get the box IP with command " + it + " : " + err)
      }
    }

    return ""
}
