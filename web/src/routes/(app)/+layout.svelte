<script lang="ts">
	import { page } from '$app/state';
	import { fly } from '$lib/motion';
	import Sidebar from '$lib/shell/Sidebar.svelte';
	import BottomNav from '$lib/shell/BottomNav.svelte';
	import MobileBar from '$lib/shell/MobileBar.svelte';

	let { children } = $props();
	let collapsed = $state(false);
</script>

<a class="skip" href="#content">Перейти к содержимому</a>
<div class="shell" class:collapsed>
	<div class="desktop-only"><Sidebar bind:collapsed /></div>
	<div class="main-col">
		<div class="mobile-only"><MobileBar /></div>
		<main id="content" tabindex="-1">
			{#key page.url.pathname}
				<div class="page" in:fly={{ y: 6, duration: 180 }}>
					{@render children()}
				</div>
			{/key}
		</main>
	</div>
	<div class="mobile-only"><BottomNav /></div>
</div>

<style>
	.shell {
		display: flex;
		min-height: 100dvh;
	}
	.main-col {
		flex: 1;
		min-width: 0;
	}
	main {
		max-width: calc(var(--content) + 2 * var(--s5));
		margin: 0 auto;
		padding: var(--s4) var(--s4) calc(var(--bottom-nav) + var(--s6) + env(safe-area-inset-bottom));
		outline: none;
	}
	.desktop-only {
		display: none;
	}
	@media (min-width: 900px) {
		.desktop-only {
			display: block;
		}
		.mobile-only {
			display: none;
		}
		main {
			padding: var(--s6) var(--s5) var(--s7);
		}
	}
	.skip {
		position: absolute;
		left: -9999px;
		top: 8px;
		z-index: 200;
		padding: 8px 12px;
		background: var(--surface);
		border-radius: 8px;
		box-shadow: var(--shadow-2);
	}
	.skip:focus {
		left: 8px;
	}
</style>
