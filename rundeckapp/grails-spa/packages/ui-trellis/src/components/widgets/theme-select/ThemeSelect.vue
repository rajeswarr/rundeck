<template>
    <div style="padding: 20px;">
        <div class="form-group">
          <label>Theme</label>
          <select class="form-control select" v-model="theme">
            <option v-for="themeOpt in themes" :key="themeOpt">{{themeOpt}}</option>
          </select>
        </div>
    </div>
</template>

<script lang="ts">
import Vue from 'vue'

import {Component, Inject, Prop, Watch} from 'vue-property-decorator'
import {Observer} from 'mobx-vue'

import {RootStore} from '../../../stores/RootStore'
import { ThemeStore } from '../../../stores/Theme'


@Observer
@Component({components: {}})
export default class ThemeSelect extends Vue {
    @Inject()
    private readonly rootStore!: RootStore

    themes = ['azure silver', 'classic black' , 'green light' , 'maroon ash',  'purple snow', 'violet matte' ]

    theme = ''

    themeStore!: ThemeStore

    created() {
        this.themeStore = this.rootStore.theme
        //this.theme = this.themeStore.userPreferences.theme!

         if( this.themeStore.userPreferences.theme == 'purple' ){
           this.theme = 'purple snow'
         }
         else if( this.themeStore.userPreferences.theme == 'green' ){
           this.theme = 'green light'
         }
         else if( this.themeStore.userPreferences.theme == 'maroon' ){
           this.theme = 'maroon ash'
         }
         else if( this.themeStore.userPreferences.theme == 'azure' ){
           this.theme = 'azure silver'
         }
        //  else if( this.themeStore.userPreferences.theme == 'pnight' ){
        //    this.theme = 'purple night'
        //  }
         else if( this.themeStore.userPreferences.theme == 'violet' ){
           this.theme = 'violet matte'
         }
         else if( this.themeStore.userPreferences.theme == 'black' ){
           this.theme = 'classic black'
         }

        if (this.themeStore.userPreferences.theme) {
          sessionStorage.setItem('selected-theme', this.themeStore.userPreferences.theme);
        }
    }

    @Watch('theme')
    handleThemeChange(newVal: any) {
      
       //let val =(newVal == 'purple night') ? 'pnight' :((newVal == 'classic black') ? 'black' : newVal.split(' ')[0]);
        let val =((newVal == 'classic black') ? 'black' : newVal.split(' ')[0]);
      //  let url = `https://localhost:4200/#/?theme=`+ val;
        this.themeStore.setUserTheme(val);
        sessionStorage.setItem('selected-theme', val);
        console.log('theme select', val);
        // if (( document as any).getElementById('reports-frame')) {
        //   console.log('theme select');
        //   (document as any).getElementById('reports-frame').setAttribute('src', url);
        // }

    }
}
</script>