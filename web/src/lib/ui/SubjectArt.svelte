<script lang="ts">
	import { subjectById } from '$lib/data.svelte';
	import { initials } from '$lib/format';

	// Обложка предмета: загруженная картинка или градиент цвета предмета с крупными инициалами.
	let {
		id,
		name,
		color,
		avatar = undefined,
		class: cls = ''
	}: { id: number; name: string; color: string; avatar?: string | null; class?: string } = $props();

	const img = $derived(avatar === undefined ? (subjectById(id)?.avatar ?? null) : avatar);
</script>

<span class="art {cls}" style:--c={color} aria-hidden="true">
	{#if img}
		<img src="/api/avatars/{img}-256.webp" alt="" loading="lazy" decoding="async" />
	{:else}
		<span class="letters">{initials(name)}</span>
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
	.letters {
		position: absolute;
		right: 6%;
		bottom: -4%;
		font-size: min(56cqh, 44cqw);
		font-weight: 800;
		line-height: 1;
		letter-spacing: -0.06em;
		color: rgb(255 255 255 / 0.32);
	}
</style>
