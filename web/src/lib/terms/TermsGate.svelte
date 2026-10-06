<script lang="ts">
	import { onMount } from 'svelte';
	import { ScrollText } from '@lucide/svelte';
	import { session } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import TermsText from './TermsText.svelte';
	import { acceptTerms, loadTerms, type TermsView } from './terms';

	// Согласие с правилами для тех, кто был на сайте до них (или правила поменялись). Новички
	// принимают их галочкой при регистрации. Закрыть окно, не приняв, нельзя — только выйти.
	let terms = $state<TermsView | null>(null);
	let agreed = $state(false);
	let busy = $state(false);
	let root: HTMLElement | undefined = $state();

	onMount(() => {
		root?.focus();
		loadTerms()
			.then((t) => (terms = t))
			.catch(toastError);
	});

	async function accept() {
		if (!terms) return;
		busy = true;
		try {
			await acceptTerms(terms.version);
			if (session.me) session.me.user.termsAccepted = true;
		} catch (e) {
			toastError(e);
			// Правила успели поменяться — показываем новые.
			terms = await loadTerms().catch(() => terms);
			agreed = false;
		} finally {
			busy = false;
		}
	}

	const leave = () => import('$lib/profile/logout').then((m) => m.logout());
</script>

<div
	class="gate"
	role="dialog"
	aria-modal="true"
	aria-labelledby="terms-title"
	tabindex="-1"
	bind:this={root}
>
	<div class="box card">
		<header>
			<ScrollText size={22} />
			<h2 id="terms-title">Правила сайта</h2>
		</header>
		<p class="muted">
			{session.me?.user.termsAccepted === false && terms?.updatedAt
				? 'Правила обновились. Прочитайте и примите их, чтобы продолжить.'
				: 'Прочитайте и примите правила, чтобы продолжить.'}
		</p>
		<div class="text">
			<TermsText extraHtml={terms?.extraHtml ?? ''} />
		</div>
		<label class="agree">
			<input type="checkbox" bind:checked={agreed} />
			<span>Я прочитал(а) и принимаю правила сайта</span>
		</label>
		<div class="actions">
			<Button variant="ghost" onclick={leave}>Выйти</Button>
			<Button variant="primary" disabled={!agreed || !terms} loading={busy} onclick={accept}
				>Принимаю</Button
			>
		</div>
	</div>
</div>

<style>
	.gate {
		position: fixed;
		inset: 0;
		z-index: 1100;
		display: grid;
		place-items: center;
		padding: var(--s4);
		background: var(--overlay);
		outline: none;
	}
	.box {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		width: min(640px, 100%);
		max-height: calc(100dvh - 32px);
		padding: var(--s5);
		box-shadow: var(--shadow-3);
	}
	header {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	h2 {
		margin: 0;
		font-size: 22px;
	}
	.box > p {
		margin: 0;
	}
	.text {
		overflow: auto;
		min-height: 0;
		flex: 1;
		padding: var(--s3) var(--s4);
		border-radius: var(--r);
		background: var(--surface-2);
		font-size: 14.5px;
		overscroll-behavior: contain;
	}
	.agree {
		display: flex;
		align-items: center;
		gap: 10px;
		font-weight: 600;
		cursor: pointer;
	}
	.agree input {
		width: 20px;
		height: 20px;
		flex: none;
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: var(--s2);
	}
</style>
