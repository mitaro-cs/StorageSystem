<script lang="ts">
	import { onMount } from 'svelte';
	import { ScrollText } from '@lucide/svelte';
	import { put } from '$lib/api';
	import { session } from '$lib/session.svelte';
	import { loadTerms, type TermsView } from '$lib/terms/terms';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import SectionHead from '$lib/ui/SectionHead.svelte';

	// Правила группы поверх общих (Markdown). Поменяли – каждого спросят согласие снова.
	let terms = $state<TermsView | null>(null);
	let extra = $state('');
	let busy = $state(false);
	onMount(() => {
		loadTerms()
			.then((t) => {
				terms = t;
				extra = t.extraMd;
			})
			.catch(() => {});
	});

	async function save() {
		busy = true;
		try {
			terms = await put<TermsView>('/api/admin/terms', { extra });
			// Себя тоже спросят: правила поменялись.
			if (session.me) session.me.user.termsAccepted = false;
			toast('Правила сохранены – участники примут их при следующем открытии', 'ok');
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}
</script>

<section class="card form">
	<SectionHead
		icon={ScrollText}
		tone="violet"
		title="Правила сайта"
		text="Общие правила и условия хранения данных уже есть. Здесь можно дописать правила своей группы – после сохранения каждый примет их заново."
	/>
	<textarea
		class="input"
		rows="5"
		maxlength="20000"
		bind:value={extra}
		placeholder="Например: в чате – только по учёбе; ответы на контрольные не выкладывать до конца пары."
		aria-label="Правила группы"></textarea>
	<div class="row wrap">
		<Button
			variant="primary"
			loading={busy}
			disabled={!terms || extra.trim() === terms.extraMd}
			onclick={save}>Сохранить</Button
		>
		<Button variant="ghost" href="/terms">Посмотреть правила</Button>
	</div>
</section>
