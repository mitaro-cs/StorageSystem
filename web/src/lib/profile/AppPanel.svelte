<script lang="ts">
	import { BookOpenText, Info, SlidersHorizontal, Smartphone } from '@lucide/svelte';
	import { canToggleManage, isAdmin, session, setManageMode } from '$lib/session.svelte';
	import { welcome } from '$lib/onboarding.svelte';
	import { install, installed, pwa } from '$lib/pwa.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Switch from '$lib/ui/Switch.svelte';

	// Приложение: установка на телефон, знакомство с сайтом, версия; хосту – режим управления.
	const me = $derived(session.me!);

	async function setManage(on: boolean) {
		try {
			await setManageMode(on);
			toast(on ? 'Кнопки управления снова видны' : 'Кнопки управления скрыты', 'ok');
		} catch (e) {
			toastError(e);
		}
	}
</script>

{#if canToggleManage()}
	<section class="card pane">
		<div class="pane-title">
			<SlidersHorizontal size={18} />
			<h3>Режим управления</h3>
			<span class="switch"
				><Switch checked={me.user.manageMode} label="Режим управления" onchange={setManage} /></span
			>
		</div>
		<p class="muted small">
			Кнопки администратора: приглашения, права, сервер, модерация. Выключите – и сайт выглядит так
			же, как у участников.
		</p>
	</section>
{/if}

<section class="card pane">
	<div class="pane-title">
		<Smartphone size={18} />
		<h3>Приложение на телефон</h3>
	</div>
	<p class="muted small">
		{installed()
			? 'Уже установлено: Campus открывается с экрана «Домой», без адресной строки.'
			: 'Установите сайт как приложение: откроется без адресной строки, со своим значком, а задания и новости будут под рукой без сети.'}
	</p>
	<div class="row wrap">
		{#if pwa.canInstall}<Button variant="primary" onclick={install}>Установить</Button>{/if}
		<Button href="/install">Как установить – по шагам</Button>
	</div>
</section>

<section class="card pane">
	<div class="pane-title">
		<BookOpenText size={18} />
		<h3>Как пользоваться</h3>
	</div>
	<p class="muted small">
		Тур по сайту – то же, что при первом входе: полминуты, по главным кнопкам.
	</p>
	<div><Button onclick={() => (welcome.open = true)}>Пройти тур</Button></div>
</section>

<section class="card pane">
	<div class="pane-title">
		<Info size={18} />
		<h3>О программе</h3>
	</div>
	<dl class="kv">
		<div>
			<dt>Версия</dt>
			<dd class="num">Campus {me.instance.version}</dd>
		</div>
	</dl>
	<div class="row wrap">
		{#if isAdmin()}<Button href="/settings?tab=updates">Проверить обновления</Button>{/if}
		<Button variant="ghost" href="/terms">Правила и конфиденциальность</Button>
	</div>
</section>

<style>
	.switch {
		margin-left: auto;
	}
</style>
