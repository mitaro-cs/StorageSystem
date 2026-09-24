<script lang="ts">
	import { subjectById } from '$lib/data.svelte';
	import { subjectIcon } from '$lib/subjectIcons';

	// Обложка предмета: загруженная картинка или градиент цвета предмета с крупной иконкой.
	let {
		id,
		name,
		color,
		avatar = undefined,
		icon = undefined,
		class: cls = ''
	}: {
		id: number;
		name: string;
		color: string;
		avatar?: string | null;
		icon?: string | null;
		class?: string;
	} = $props();

	const img = $derived(avatar === undefined ? (subjectById(id)?.avatar ?? null) : avatar);
	const glyph = $derived(subjectIcon(icon === undefined ? subjectById(id)?.icon : icon, name));
</script>

<span class="art {cls}" style:--c={color} aria-hidden="true">
	{#if img}
		<img src="/api/avatars/{img}-256.webp" alt="" loading="lazy" decoding="async" />
	{:else}
		<span class="glyph"><glyph.icon size="100%" strokeWidth={1.6} /></span>
	{/if}
</span>

<style>
	.art {
		position: relative;
		display: block;
		overflow: hidden;
		container-type: size;
		border-radius: var(--r);
		background:
			radial-gradient(120% 90% at 18% 8%, rgb(255 255 255 / 0.35), transparent 55%),
			linear-gradient(150deg, var(--c), color-mix(in srgb, var(--c) 55%, #000));
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.glyph {
		position: absolute;
		right: 8%;
		bottom: 8%;
		width: min(58cqh, 58cqw);
		height: min(58cqh, 58cqw);
		color: rgb(255 255 255 / 0.9);
		filter: drop-shadow(0 2px 6px rgb(0 0 0 / 0.18));
	}
	.glyph :global(svg) {
		width: 100%;
		height: 100%;
	}
</style>
