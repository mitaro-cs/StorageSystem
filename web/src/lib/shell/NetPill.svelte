<script lang="ts">
	import { CloudOff, CloudUpload, WifiOff } from '@lucide/svelte';
	import { slide } from '$lib/motion';
	import { offline } from '$lib/offline/engine';
	import { pwa } from '$lib/pwa.svelte';

	// Плашка связи над страницей (0.9): «Нет сети», «Сервер не работает», «Отправляем» + очередь.
	// Отдельным файлом (1.0.2): нужна редко, а её значки утяжеляли «Сегодня».
</script>

<div class="net" role="status" transition:slide|global>
	<span class="pill" class:send={!pwa.offline}>
		{#if !pwa.offline}<CloudUpload size={14} /> Отправляем
		{:else if pwa.network}<CloudOff size={14} /> Сервер не работает
		{:else}<WifiOff size={14} /> Нет сети{/if}{#if offline.pending}<b>{offline.pending}</b>{/if}
	</span>
</div>

<style>
	.net {
		display: flex;
		justify-content: center;
		padding: 6px 16px 0;
	}
	.pill {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 4px 12px;
		border-radius: var(--r-full);
		background: var(--amber-soft);
		color: var(--amber);
		font-size: 12.5px;
		font-weight: 650;
	}
	.pill.send {
		background: var(--surface-2);
		color: var(--text-2);
	}
	.pill b {
		min-width: 18px;
		padding: 0 5px;
		border-radius: var(--r-full);
		background: currentColor;
		text-align: center;
	}
	.pill b {
		color: var(--surface);
	}
</style>
