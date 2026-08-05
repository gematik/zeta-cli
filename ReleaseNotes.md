<img align="right" width="250" height="47" src="images/gematik-logo.png"/> <br/>    
 
# Release Notes ZETA CLI
## Release 0.9.2
### changes
- Fix `.kon` `expectedHost` TLS verification: the option delegated to a deny-all JDK verifier and never matched
- Verify the hostname against subjectAltNames, falling back to the subject CN for SAN-less self-signed Konnektor certs (e.g. `CN=server`)

## Release 0.9.1
### changes
- Bump `zeta-sdk` dependency to 1.2.5
- Add `--profile-version` option to `zeta vsdm get` (defaults to 1.1; the profile version was previously hard-coded to 1.0)

## Release 0.9.0
### changes
- Migrate CLI state storage to SQLite
- Add `zeta vsdm get` — read a patient's VSDM bundle from a PoPP token
- Add new stress storm scenarios and bound the live client set
- Track and report SDK-state expiry in stress runs

## Release 0.8.3
### changes
- Validate each SDK step's success via status checks in stress runs
- Document the `zeta vsdm` command and add the stress guide

## Release 0.8.2
### changes
- Release-flow and packaging retag (no functional changes over 0.8.1)

## Release 0.8.1
### changes
- Update the release flow (`just release` recipe)
