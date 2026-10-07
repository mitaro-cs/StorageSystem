<script lang="ts">
	import { onMount } from 'svelte';
	import { Crown } from '@lucide/svelte';
	import { get } from '$lib/api';
	import AccessPanel from './AccessPanel.svelte';
	import BackupsPanel from './BackupsPanel.svelte';
	import HostsPanel from './HostsPanel.svelte';
	import PeerPanel from './PeerPanel.svelte';
	import StatusPanel from './StatusPanel.svelte';
	import type { PeerView } from './types';

	// Копия (второй из двух компьютеров, 0.9.7): управление сайтом и группой – как обычно, а доступ
	// для группы и резервные копии – дело основного: здесь их не показываем.
	let copy = $state(false);
	let main = $state<string | null>(null);
	onMount(() => {
		get<PeerView>('/api/host/peers')
			.then((v) => {
				copy = v.role === 'second';
				main = v.serving;
			})
			.catch(() => {});
	});
</script>

<!-- Всё про сам сервер: как до него добраться, копии данных и его состояние. -->
{#if copy}
	<div class="card pane on-main">
		<span class="crown" aria-hidden="true"><Crown size={20} /></span>
		<p>
			<strong>Этот компьютер – копия.</strong> Сайтом и группой управляйте как обычно – изменения
			уходят на основной{#if main}&nbsp;«{main}»{/if}. Доступ для группы и резервные копии
			настраиваются там – или сделайте основным этот ниже.
		</p>
	</div>
{:else}
	<h2 class="head">Доступ для группы</h2>
	<AccessPanel />
{/if}

<!-- Заголовок у раздела свой: его нет, если сайт не в приложении хоста. -->
<HostsPanel />
<PeerPanel />

{#if !copy}
	<h2 class="head">Резервные копии</h2>
	<BackupsPanel />
{/if}

<h2 class="head">Состояние</h2>
<StatusPanel />

<style>
	.head {
		margin: var(--s6) 0 var(--s3);
	}
	.head:first-child {
		margin-top: var(--s2);
	}
	.on-main {
		display: flex;
		gap: var(--s3);
		align-items: flex-start;
		margin-top: var(--s2);
	}
	.on-main p {
		margin: 0;
	}
	.crown {
		display: grid;
		place-items: center;
		flex: none;
		width: 38px;
		height: 38px;
		border-radius: 12px;
		background: var(--surface-2);
		color: var(--text-2);
	}
</style>
