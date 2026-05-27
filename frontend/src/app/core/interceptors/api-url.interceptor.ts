import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../../../environments/environment';

export const apiUrlInterceptor: HttpInterceptorFn = (req, next) => {
  const isApiRequest = req.url.startsWith('/api') || req.url.startsWith('/auth');
  if (environment.apiUrl && isApiRequest) {
    return next(req.clone({ url: `${environment.apiUrl}${req.url}` }));
  }
  return next(req);
};
