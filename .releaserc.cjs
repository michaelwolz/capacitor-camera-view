const rcBranch = process.env.SEMANTIC_RELEASE_RC_BRANCH;
const branches =
  rcBranch && rcBranch !== 'main'
    ? ['main', { name: rcBranch, channel: 'prerelease', prerelease: 'rc' }]
    : ['main'];

module.exports = {
  branches,
  tagFormat: '${version}',
  plugins: [
    ['@semantic-release/commit-analyzer'],
    ['@semantic-release/release-notes-generator'],
    ['@semantic-release/changelog', { changelogFile: 'CHANGELOG.md' }],
    ['@semantic-release/npm', { npmPublish: true }],
    '@semantic-release/github',
    [
      '@semantic-release/git',
      {
        assets: ['package.json', 'package-lock.json', 'CHANGELOG.md'],
        message: 'chore(release): ${nextRelease.version} [skip ci]\n\n${nextRelease.notes}',
      },
    ],
  ],
};
