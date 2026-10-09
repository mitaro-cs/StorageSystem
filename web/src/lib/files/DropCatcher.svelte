<script lang="ts">
	import { palette } from '$lib/shell/palette.svelte';
	import { deliver, drop } from './drop.svelte';
	import DropOverlay from './DropOverlay.svelte';

	// Файлы – в любое место окна (0.9.8): над окном плашка «Отпустите…», брошенное и вставленное
	// получает открытое поле вложений, а без него открывается «Загрузить файл».
	let depth = 0;
	const withFiles = (e: DragEvent) => !!e.dataTransfer?.types.includes('Files');

	function take(files: File[]) {
		if (files.length && !deliver(files)) palette.upload = true;
	}
</script>

<svelte:window
	ondragenter={(e) => {
		if (!withFiles(e)) return;
		e.preventDefault();
		depth++;
		drop.over = true;
	}}
	ondragover={(e) => {
		if (withFiles(e)) e.preventDefault();
	}}
	ondragleave={(e) => {
		if (withFiles(e) && --depth <= 0) {
			depth = 0;
			drop.over = false;
		}
	}}
	ondrop={(e) => {
		if (!withFiles(e)) return;
		e.preventDefault();
		depth = 0;
		drop.over = false;
		take(Array.from(e.dataTransfer?.files ?? []));
	}}
	onpaste={(e) => {
		const files = Array.from(e.clipboardData?.files ?? []);
		if (!files.length) return;
		e.preventDefault();
		take(files);
	}}
/>

{#if drop.over}<DropOverlay />{/if}
