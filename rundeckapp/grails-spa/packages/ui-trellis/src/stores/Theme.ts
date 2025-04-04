import { autorun, observable } from "mobx";

import {RundeckClient} from '@rundeck/client'
import {RootStore} from './RootStore'

export enum Theme {
    // system = "system",
    // dark = "dark",
    // light = "light",
    purple = "purple",
    green = "green",
    maroon = "maroon",
    azure = "azure",
 // pnight = "pnight",
    violet= "violet",
    black= "black"

}

export enum PrefersColorScheme {
    // dark = 'dark',
    // light = 'light',
    purple = "purple",
    green = "green",
    maroon = "maroon",
    azure = "azure",
 // pnight = "pnight",
    violet= "violet",
    black= "black"
}

interface UserPreferences {
    theme?: Theme
}

const THEME_USER_PREFERENCES_KEY = 'theme-user-preferences';


export class ThemeStore {
    @observable userPreferences: UserPreferences = {theme: Theme.purple}
    @observable theme!: Theme
    @observable prefersColorScheme!: PrefersColorScheme 

    themeMediaQuery: MediaQueryList

    constructor() {
        this.loadConfig()
        this.setTheme()
        this.loadLogo();

        this.themeMediaQuery = window.matchMedia('(prefers-color-scheme: purple)')
        this.handleSystemChange(this.themeMediaQuery)

        // Safari <14
        if (typeof this.themeMediaQuery.addListener != 'undefined')
            this.themeMediaQuery.addListener(this.handleSystemChange)
        else
            this.themeMediaQuery.addEventListener('change', this.handleSystemChange)

    }

    setUserTheme(theme: Theme) {
        this.userPreferences.theme = theme
        this.setTheme()
        this.saveConfig()
    }

    setTheme() {
        if (this.userPreferences.theme == Theme.purple)
            this.theme = Theme[this.prefersColorScheme]
        else
            this.theme = this.userPreferences.theme!

        document.documentElement.dataset.colorTheme = this.theme
    }

    handleSystemChange = (e: MediaQueryListEvent | MediaQueryList) => {
        if (e.matches)
            this.prefersColorScheme = PrefersColorScheme.green
        else
            this.prefersColorScheme = PrefersColorScheme.purple

        this.setTheme()
    }

    loadConfig() {
        const settings = localStorage.getItem(THEME_USER_PREFERENCES_KEY)
  
        if (settings) {
            try {
                const config = JSON.parse(settings)
                Object.assign(this.userPreferences, config)
            } catch (e) {
                localStorage.removeItem(THEME_USER_PREFERENCES_KEY)
            }
        }
    }

    saveConfig() {
        if(this.userPreferences) {
            localStorage.setItem(THEME_USER_PREFERENCES_KEY, JSON.stringify(this.userPreferences));
        }
        else {
            localStorage.setItem(THEME_USER_PREFERENCES_KEY, JSON.stringify({theme: Theme.purple}));
        }
        this.loadLogo();
    }

    loadLogo() {
        console.log(localStorage.getItem('theme-user-preferences'));
        let themeColor = JSON.parse(localStorage.getItem('theme-user-preferences') || '{}') ? JSON.parse(localStorage.getItem('theme-user-preferences') || '{}')?.theme : 'purple';
        console.log(themeColor);
        document.querySelector('html')?.removeAttribute('class');
       // document.querySelector('html')?.classList.add(themeColor);
        if(themeColor) {
            document.querySelector('html')?.classList.add(themeColor);
        } else{
            document.querySelector('html')?.classList.add('purple');
        }
    }
}