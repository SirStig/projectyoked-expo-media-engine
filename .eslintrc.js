module.exports = {
    ignorePatterns: ['example/', 'docs/'],
    env: {
        browser: true,
        es2021: true,
        node: true,
        jest: true,
    },
    extends: [
        'eslint:recommended',
    ],
    parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
    },
    rules: {
        'no-console': 'off',
        'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    },
    overrides: [
        {
            files: ['tests/**/*.test.js'],
            env: {
                jest: true,
            },
        },
    ],
};
