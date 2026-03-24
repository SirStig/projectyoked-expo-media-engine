# Contributing to @projectyoked/expo-media-engine

Thank you for your interest in contributing to this project! We welcome contributions from the community.

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/projectyoked-expo-media-engine.git
   cd projectyoked-expo-media-engine
   ```
3. **Install dependencies**:
   ```bash
   npm install
   ```

## Documentation

User-facing API documentation for stable vs pre-release lines lives in `site/` and is deployed to GitHub Pages (see [DOCUMENTATION.md](DOCUMENTATION.md)). Update those HTML pages when you change public APIs or install instructions for either release line.

## Development Workflow

### Setting Up for Development

This is an Expo native module that requires native development setup:

**iOS Development:**
```bash
cd ios
pod install
```

**Android Development:**
Ensure you have Android Studio installed with SDK 26+.

### Making Changes

1. Create a new branch for your feature or fix:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes in the appropriate files:
   - JavaScript API: `src/index.js`
   - iOS native code: `ios/MediaEngineModule.swift`
   - Android native code: `android/src/main/java/com/projectyoked/mediaengine/`

3. Test your changes thoroughly on both iOS and Android

### Code Style

- Follow existing code style in the repository
- Use meaningful variable and function names
- Add comments for complex logic
- Keep functions focused and single-purpose

### Testing

Before submitting your changes:

1. Test on **both iOS and Android**
2. Verify all existing functionality still works
3. Test edge cases and error conditions
4. Ensure no console warnings or errors

### Committing Changes

- Write clear, descriptive commit messages
- Use conventional commits format:
  ```
  feat: add new feature
  fix: resolve specific bug
  docs: update documentation
  chore: update dependencies
  ```

### Submitting a Pull Request

1. **Push your branch** to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Create a Pull Request** on GitHub from your branch to the main repository

3. **Describe your changes** clearly in the PR description:
   - What problem does it solve?
   - What changes were made?
   - How to test?
   - Screenshots/videos if UI-related

4. **Link any related issues** in the PR description

## Reporting Issues

When reporting issues, please include:

- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Platform (iOS/Android)
- Device/simulator information
- Expo SDK version
- React Native version
- Error messages and stack traces

## Feature Requests

We welcome feature requests! Please:

- Search existing issues first to avoid duplicates
- Describe the use case clearly
- Explain why this feature would be useful
- Provide examples if possible

## Code of Conduct

- Be respectful and inclusive
- Welcome newcomers
- Focus on constructive feedback
- Help maintain a positive community

## Questions?

- Open an issue with the `question` label
- Check existing issues and documentation first

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

## Thank You!

Your contributions help make this project better for everyone. We appreciate your time and effort! 🙏
