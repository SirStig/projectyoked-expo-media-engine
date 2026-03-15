# Security Policy

## Supported Versions

We actively support the following versions with security updates:

| Version           | Supported          |
| ----------------- | ------------------ |
| 1.0.0-alpha.x     | :white_check_mark: |
| 0.1.x             | :white_check_mark: |

## Reporting a Vulnerability

We take security vulnerabilities seriously. If you discover a security issue, please follow these steps:

1. **Do NOT open a public issue** - This could put users at risk

2. **Email the maintainers directly** with details:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

3. **Allow time for response** - We aim to respond within 48 hours

4. **Coordinated disclosure** - We'll work with you to:
   - Verify the issue
   - Develop a fix
   - Release a patch
   - Credit you (if desired) in the security advisory

## Security Best Practices

When using this library:

- **File paths**: Always validate and sanitize file paths before passing to the module
- **User input**: Never pass unsanitized user input directly to video/audio processing functions
- **Permissions**: Ensure your app requests proper permissions for file access
- **Storage**: Be mindful of storage space when processing large video files
- **Error handling**: Always wrap module calls in try-catch blocks

## Scope

This security policy applies to the native module code:
- JavaScript API (`src/index.js`)
- iOS native module (`ios/MediaEngineModule.swift`)
- Android native module (`android/src/`)

## Updates

Security updates will be released as patch versions and documented in the [CHANGELOG](CHANGELOG.md).

Thank you for helping keep this project secure! 🔒
