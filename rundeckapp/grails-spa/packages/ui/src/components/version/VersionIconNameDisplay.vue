<template>
    <span class="rundeck-version-icon" >
        <i class="glyphicon" :class="['glyphicon-'+icon]"></i>
        <span class="rundeck-version-name">{{text}}</span>
    </span>
</template>
<script lang="ts">
import Vue from 'vue'
export default Vue.extend({
  props: {
    icon:{
      type:String,required:true
    },
    text:{
      type:String,required:true
    },
    color:{
      type:String,required:true
    }
  }
})
</script>
