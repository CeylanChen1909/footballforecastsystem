<template>
  <router-view />
</template>

<script setup>
</script>

<style>
:root {
  color-scheme: light;
  --ff-bg: #f5f7fb;
  --ff-card: rgba(255, 255, 255, 0.88);
  --ff-border: rgba(15, 23, 42, 0.08);
  --ff-text: #172033;
  --ff-muted: #6b7280;
  --ff-primary: #3b82f6;
  --ff-primary-2: #2563eb;
}

* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body, #app {
  min-height: 100%;
}

body {
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  background:
    radial-gradient(circle at top left, rgba(59, 130, 246, 0.10), transparent 28%),
    radial-gradient(circle at top right, rgba(16, 185, 129, 0.08), transparent 24%),
    var(--ff-bg);
  color: var(--ff-text);
}

a {
  text-decoration: none;
  color: inherit;
}

::selection {
  background: rgba(59, 130, 246, 0.18);
}
</style>
