<template>
  <div id="app" class="container oto-plugins">
    <Overlay/>
    <router-view></router-view>
  </div>
</template>

<script>
import Overlay from "./components/Overlay";
export default {
  name: "PluginApplication",
  components: {
    Overlay
  },
  computed: {},
  methods: {}
};
</script>
<style lang="scss">
</style>
