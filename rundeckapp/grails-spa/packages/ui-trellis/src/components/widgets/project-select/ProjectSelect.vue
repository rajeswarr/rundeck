<template>
    <div class="widget-wrapper">
        <div class="widget-section" style="flex-grow: 1; flex-shrink: 1;">
            <div style="padding: 10px 10px 0 10px;">
                <div class="form-group form-group-sm has-feedback has-search">
                    <i class="fas fa-search form-control-feedback"/>
                    <input
                        ref="search"
                        type="text" 
                        class="form-control form-control-sm"
                        v-model="searchTerm"
                        placeholder="Search all projects"/>
                </div>
            </div>
            <Skeleton :loading="!projects.loaded">
                <RecycleScroller @foo="alert('Foo')"
                    ref="scroller"
                    :items="projects.search(searchTerm)"
                    :item-size="25"
                    :key="projects.search(searchTerm).length"
                    v-slot="{ item }"
                    key-field="name"
                    class="scroller"
                >
                    <div role="button" tabindex="0" class="scroller__item" :title="item.name" 
                        @click="itemClicked(item)"
                        @keypress.enter="itemClicked(item)">
                        <span>{{item.label || item.name}}</span>
                    </div>
                </RecycleScroller>
            </Skeleton>
        </div>
        <div class="widget-section" style="height: 40px; flex-grow: 0; flex-shrink: 0;border-top: solid 1px grey; padding-left: 10px">
            <a class="text-info" :href="allProjectsLink" @click.prevent="allClicked" @keypress.enter.prevent="allClicked">View All Projects</a>
        </div>
    </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {Component, Inject} from 'vue-property-decorator'
import { autorun } from 'mobx'
import {Observer} from 'mobx-vue'
import PerfectScrollbar from 'perfect-scrollbar'
import { RecycleScroller } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'

import { getAppLinks } from '../../../rundeckService'
import {RootStore} from '../../../stores/RootStore'
import { ProjectStore, Project } from '../../../stores/Projects'
import Skeleton from '../../skeleton/Skeleton.vue'

RecycleScroller.updated = function() {
    if (!this.ps)
        this.$nextTick().then(() => {this.ps = new PerfectScrollbar(this.$el, {minScrollbarLength: 20})})
    else
        this.ps.update()
}

const destroy = RecycleScroller.beforeDestroy
RecycleScroller.beforeDestroy = function() {
    destroy.bind(this)()
    if (this.ps) {
        try {
            this.ps.destroy()
            this.ps = null
        } catch {}
    }
}

@Observer
@Component({components: {
    RecycleScroller,
    Skeleton
}})
export default class ProjectSelect extends Vue {
    @Inject()
    private readonly rootStore!: RootStore

    projects!: ProjectStore

    ps!: PerfectScrollbar

    searchTerm: string = ''

    get allProjectsLink(): string {
        return getAppLinks().menuHome
    }

    created() {
        this.projects = this.rootStore.projects
        this.projects.load()
    }

    mounted() {
        autorun(() => {
            if (this.projects.projects.length) {
                /** May be necessary for virtual scroller to update */
                this.$forceUpdate()
            }
        })
        this.$nextTick().then(() => {
            (<HTMLElement>this.$refs['search']).focus()
        })
    }

    itemClicked(project: Project) {
        this.$emit('project:selected', project)
    }

    allClicked() {
        this.$emit('project:select-all')
    }
}
</script>

<style scoped lang="scss">
.widget-wrapper {
    display: flex;
    flex-direction: column;
    justify-content: flex-start;
    height: 100%;
    max-width: 500px;
    overflow: hidden;
    min-height: 0;
}

.widget-section {
    display: flex;
    flex-direction: column;
    min-height: 0;
    justify-content: center;
}


.scroller {
    height: 100%;
    overflow-x: hidden;
    padding-right: 5px;
    flex-grow: 1;
}

.scroller__item {
    position: relative;
    padding-left: 10px;
    width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    cursor: pointer;

    outline: none;

    &:hover::before, &:focus::before {
        position: absolute;
        content: "";
        height: 100%;
        border-left: 3px solid var(--brand-color);
        margin-left: -10px;
    }
}

.has-search .form-control-feedback {
    right: initial;
    left: 0;
    top: 8px;
}

.has-search .form-control {
    padding-right: 12px;
    padding-left: 34px;
}

.skeleton {
    --skel-color: #eeeeee !important;
    margin: 0 10px 0 10px;
}

</style>