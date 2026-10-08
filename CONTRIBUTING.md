# Contributing

## Publishing

The plugin stays off JetBrains Marketplace until the owner publishes it. These steps are one-time setup and a publish command.

1. Create a JetBrains Marketplace account at https://plugins.jetbrains.com. Sign in with the same JetBrains account you use in the IDE.

2. Upload the first version by hand. The Marketplace still requires this. The first upload creates the vendor profile and accepts the Developer Agreement. Follow https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html. Upload `build/distributions/simdref-<version>.zip` (from `./gradlew buildPlugin`).

3. Create a permanent Marketplace token. Go to My Tokens in the Marketplace profile (see https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html). Copy the `perm:...` value.

4. Generate the signing key and the certificate chain. Run each openssl command once. The commands come from https://plugins.jetbrains.com/docs/intellij/plugin-signing.html.

   ```
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 365 -out chain.crt
   ```

   The first command asks for a password. This password is `PRIVATE_KEY_PASSWORD`. Keep `private.pem` and `chain.crt` out of the repo.

5. Set the four GitHub repository secrets. Run each command; for the first and last, `gh` asks for the value.

   ```
   gh secret set PUBLISH_TOKEN
   gh secret set CERTIFICATE_CHAIN < chain.crt
   gh secret set PRIVATE_KEY < private.pem
   gh secret set PRIVATE_KEY_PASSWORD
   ```

   `CERTIFICATE_CHAIN` holds the contents of `chain.crt`. `PRIVATE_KEY` holds the contents of `private.pem`.

6. Publish. Push a tag that starts with `v`, for example `git tag v0.1.0 && git push origin v0.1.0`. The `publish.yml` workflow runs `./gradlew publishPlugin`, which runs `signPlugin` first when the signing secrets are present. The new version shows on the Marketplace after approval (see https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html).
