<script lang="ts">
	import { CloudUpload } from '@lucide/svelte';
	import { fade } from '$lib/motion';

	// Файл тащат над окном: всё окно – место, куда его можно бросить (0.9.8, макет (app)).
</script>

<div class="drop-all" transition:fade={{ duration: 120 }}>
	<div><CloudUpload size={28} /><strong>Отпустите, чтобы загрузить</strong></div>
</div>

<style>
	.drop-all {
		position: fixed;
		inset: 0;
		z-index: 2000;
		display: grid;
		place-items: center;
		padding: 16px;
		background: color-mix(in srgb, var(--bg) 70%, transparent);
		backdrop-filter: blur(6px);
		pointer-events: none;
	}
	.drop-all div {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 10px;
		width: min(420px, 100%);
		padding: 36px 24px;
		border: 2px dashed var(--accent);
		border-radius: var(--r-lg, 24px);
		background: var(--surface);
		color: var(--accent);
		box-shadow: var(--shadow-3);
	}
</style>
