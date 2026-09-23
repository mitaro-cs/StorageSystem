<script lang="ts">
	import AccessPanel from './AccessPanel.svelte';
	import BackupsPanel from './BackupsPanel.svelte';
	import StatusPanel from './StatusPanel.svelte';
</script>

<!-- Всё про сам сервер: как до него добраться, копии данных и его состояние. -->
<h2 class="head">Доступ для группы</h2>
<AccessPanel />

<h2 class="head">Резервные копии</h2>
<BackupsPanel />

<h2 class="head">Состояние</h2>
<StatusPanel />

<style>
	.head {
		margin: var(--s6) 0 var(--s3);
	}
	.head:first-child {
		margin-top: var(--s2);
	}
</style>
