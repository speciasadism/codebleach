# CodeBleachAuth
In game: /register <password> <password> the first time, then /login <password>.
Accounts are shared with the CodeBleach website. Only whitelisted usernames can register.
1. Netlify > Environment variables: MC_KEY = a long random string. Redeploy the site.
2. Build the jar: push this folder to GitHub, open Actions, download "CodeBleachAuth". Or run `mvn package` with JDK 21.
3. Put CodeBleachAuth.jar in the server's plugins folder, start once, edit plugins/CodeBleachAuth/config.yml
   (site-url, api-key = MC_KEY), restart.
4. Only needed when server.properties has online-mode=false.
