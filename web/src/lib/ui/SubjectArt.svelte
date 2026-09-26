<script lang="ts">
	import { subjectById } from '$lib/data.svelte';
	import { subjectIcon } from '$lib/subjectIcons';
	import { icons, loadIcons } from '$lib/iconLoader.svelte';

	// Обложка предмета: фон (широкая картинка), загруженная картинка или градиент цвета предмета с
	// крупной иконкой.
	let {
		id,
		name,
		color,
		avatar = undefined,
		icon = undefined,
		cover = undefined,
		class: cls = ''
	}: {
		id: number;
		name: string;
		color: string;
		avatar?: string | null;
		icon?: string | null;
		cover?: string | null;
		class?: string;
	} = $props();

	const wide = $derived(cover === undefined ? (subjectById(id)?.cover ?? null) : cover);
	const img = $derived(avatar === undefined ? (subjectById(id)?.avatar ?? null) : avatar);
	const glyph = $derived(subjectIcon(icon === undefined ? subjectById(id)?.icon : icon, name));
	const Icon = $derived(icons.map?.[glyph.key]);
	$effect(() => {
		if (!img) loadIcons();
	});
</script>

<span class="art {cls}" style:--c={color} aria-hidden="true">
	{#if wide}
		<img
			class="cover"
			src="/api/avatars/{wide}-480.webp"
			srcset="/api/avatars/{wide}-480.webp 480w, /api/avatars/{wide}-1280.webp 1280w"
			sizes="(max-width: 700px) 100vw, 640px"
			alt=""
			loading="lazy"
			decoding="async"
		/>
	{:else if img}
		<img src="/api/avatars/{img}-256.webp" alt="" loading="lazy" decoding="async" />
	{:else}
		<span class="glyph"
			>{#if Icon}<Icon size="100%" strokeWidth={1.6} />{/if}</span
		>
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
	/* Фон проявляется мягко, а не «вспыхивает» по кускам. */
	.cover {
		animation: cover-in 420ms var(--ease) both;
	}
	@keyframes cover-in {
		from {
			opacity: 0;
			transform: scale(1.04);
		}
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
