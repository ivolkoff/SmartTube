# vox

- **Открывая диалог из пункта другого диалога, не вызывай `closeDialog()` перед асинхронным `showDialog()`**,
  а для диалога из одной кнопки ставь `enableExpandable(false)`. Иначе `clear()` закрывающегося окна стирает
  новый диалог (`AppDialogPresenter.onViewDestroyed`), а одиночная кнопка нажимается сама вместо показа
  (`AppDialogFragment.show`). (2026-11-11: вход в Яндекс)
